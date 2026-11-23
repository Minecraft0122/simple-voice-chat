package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.voice.transport.VoiceProtocol;
import de.maxhenkel.voicechat.voice.transport.TransportMode;
import de.maxhenkel.voicechat.voice.transport.ProxyMessages;
import de.maxhenkel.voicechat.voice.transport.ProxySettings;
import de.maxhenkel.voicechat.voice.transport.ProxyConfigSync;
import de.maxhenkel.voicechat.net.ProxyControlPacket;
import de.maxhenkel.voicechat.net.RequestSecretPacket;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.intercompatibility.CrossSideManager;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PacketRateLimiter;
import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.net.VersionPacket;
import de.maxhenkel.voicechat.voice.transport.VoiceVersion;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.Secret;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerVoiceEvents {

    private final Map<UUID, Integer> clientCompatibilities;
    private final java.util.Set<UUID> extendedClients = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> versionReported = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> versionWarnings = ConcurrentHashMap.newKeySet();
    private final PacketRateLimiter rateLimiter;
    private Server server;
    private final ProxyConfigSync proxyConfigSync = new ProxyConfigSync();
    private final Map<UUID, RequestSecretPacket> pendingProxyRequests = new ConcurrentHashMap<>();

    public ServerVoiceEvents() {
        clientCompatibilities = new ConcurrentHashMap<>();
        rateLimiter = new PacketRateLimiter(Voicechat.SERVER_CONFIG.tcpRateLimit.get());
        CommonCompatibilityManager.INSTANCE.onServerStarting(this::serverStarting);
        CommonCompatibilityManager.INSTANCE.onPlayerLoggedIn(this::playerLoggedIn);
        CommonCompatibilityManager.INSTANCE.onPlayerLoggedOut(this::playerLoggedOut);
        CommonCompatibilityManager.INSTANCE.onPlayerHide(this::onPlayerHide);
        CommonCompatibilityManager.INSTANCE.onPlayerShow(this::onPlayerShow);
        CommonCompatibilityManager.INSTANCE.onServerStopping(this::serverStopping);

        CommonCompatibilityManager.INSTANCE.onServerVoiceChatConnected(this::serverVoiceChatConnected);
        CommonCompatibilityManager.INSTANCE.onServerVoiceChatDisconnected(this::serverVoiceChatDisconnected);
        CommonCompatibilityManager.INSTANCE.onPlayerCompatibilityCheckSucceeded(this::playerCompatibilityCheckSucceeded);

        CommonCompatibilityManager.INSTANCE.getNetManager().proxyControlChannel.setServerListener((player, packet) -> {
            if (server != null) server.handleProxyControl(player, packet.getData());
        });
        CommonCompatibilityManager.INSTANCE.getNetManager().versionChannel.setServerListener(this::onVersionPacket);
        CommonCompatibilityManager.INSTANCE.getNetManager().requestSecretChannel.setServerListener((player, packet) -> {
            onRequestSecretPacket(player, packet);
        });
    }

    public Component getIncompatibleMessage(int clientCompatibilityVersion) {
        if (clientCompatibilityVersion <= 6) {
            return Component.literal(Voicechat.TRANSLATIONS.voicechatNotCompatibleMessage.get().formatted(BuildConstants.MOD_COMPATIBLE_VERSION, CommonCompatibilityManager.INSTANCE.getModName()));
        } else {
            return Component.translatableWithFallback("message.voicechat.incompatible_version",
                    "Your voice chat client version is not compatible with the server-side version.\nPlease install version %s of %s.",
                    Component.literal(BuildConstants.MOD_COMPATIBLE_VERSION).withStyle(ChatFormatting.BOLD),
                    Component.literal(CommonCompatibilityManager.INSTANCE.getModName()).withStyle(ChatFormatting.BOLD));
        }
    }

    public void onRequestSecretPacket(ServerPlayer player, RequestSecretPacket packet) {
        if (Voicechat.SERVER_CONFIG.isProxyMode()) {
            pendingProxyRequests.putIfAbsent(player.getUUID(), packet);
            requestProxySettings(player);
            return;
        }
        processSecretRequest(player, packet);
    }

    private void processSecretRequest(ServerPlayer player, RequestSecretPacket packet) {
        Voicechat.LOGGER.info("Received secret request of {} ({})", player.getName().getString(), packet.getCompatibilityVersion());
        if (packet.hasTransportExtension()) extendedClients.add(player.getUUID());
        if (packet.hasTransportExtension() && !versionReported.contains(player.getUUID()) && versionWarnings.add(player.getUUID())) {
            String message = VoiceVersion.unverified(packet.getCompatibilityVersion(), CommonCompatibilityManager.INSTANCE.getModVersion());
            Voicechat.LOGGER.warn("Player {} ({}): {}", player.getName().getString(), player.getUUID(), message);
            player.sendSystemMessage(Component.literal(message));
        }
        VoiceProtocol selected = VoiceProtocol.fromVersion(packet.getCompatibilityVersion()) == null ? null
                : TransportMode.parse(Voicechat.SERVER_CONFIG.getTransportMode())
                .select(Voicechat.SERVER_CONFIG.getTransportPreference(), packet.getTransportCapabilities());
        if (selected == null) {
            Voicechat.LOGGER.warn("Connected client {} has incompatible voice chat version (server={}, client={})", player.getName().getString(), Voicechat.COMPATIBILITY_VERSION, packet.getCompatibilityVersion());
            player.sendSystemMessage(getIncompatibleMessage(packet.getCompatibilityVersion()));
        } else {
            Integer previous = clientCompatibilities.putIfAbsent(player.getUUID(), selected.version());
            if (previous != null && previous != selected.version()) return;
            initializePlayerConnection(player);
        }
    }

    private void requestProxySettings(ServerPlayer player) {
        UUID nonce = proxyConfigSync.begin(player.getUUID(), System.currentTimeMillis());
        if (nonce == null) return;
        NetManager.sendToClient(player, new ProxyControlPacket(ProxyMessages.encode(ProxyMessages.CONFIG_REQUEST, 0L, nonce, new byte[0])));
        // Initial backend messages may precede the proxy's completed server-switch event.
        Server requestServer = server;
        for (int delay : new int[]{1, 3}) {
            java.util.concurrent.CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                if (requestServer == null || server != requestServer) return;
                CommonCompatibilityManager.INSTANCE.executeForPlayer(requestServer.getServer(), player,
                        () -> retryProxySettings(player, nonce), () -> {});
            });
        }
        java.util.concurrent.CompletableFuture.delayedExecutor(ProxyConfigSync.TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            if (server == null) return;
            CommonCompatibilityManager.INSTANCE.executeForPlayer(server.getServer(), player, () -> {
                if (!proxyConfigSync.expire(player.getUUID(), nonce)) return;
                if (pendingProxyRequests.remove(player.getUUID()) == null) return;
                Voicechat.LOGGER.warn("Voice proxy configuration sync timed out for {}. Install matching proxy/backend builds.", player.getUUID());
                player.sendSystemMessage(Component.literal("语音代理配置同步超时，请管理员确认代理和后端均已更新。"));
            }, () -> { if (proxyConfigSync.expire(player.getUUID(), nonce)) pendingProxyRequests.remove(player.getUUID()); });
        });
    }

    private void retryProxySettings(ServerPlayer player, UUID nonce) {
        if (!proxyConfigSync.isPending(player.getUUID(), nonce, System.currentTimeMillis())) return;
        NetManager.sendToClient(player, new ProxyControlPacket(ProxyMessages.encode(ProxyMessages.CONFIG_REQUEST, 0L, nonce, new byte[0])));
    }

    public void onProxySettings(ServerPlayer player, ProxyMessages.Message message) {
        if (!Voicechat.SERVER_CONFIG.isProxyMode() || message.generation() != 0L) return;
        ProxySettings settings = ProxySettings.decode(message.payload());
        if (!proxyConfigSync.accept(player.getUUID(), message.session(), System.currentTimeMillis())) return;
        Voicechat.SERVER_CONFIG.applyProxySettings(settings);
        Voicechat.LOGGER.info("Synchronized voice proxy configuration: mode={}, preference={}, port={}", settings.mode(), settings.preference(), settings.port());
        RequestSecretPacket request = pendingProxyRequests.remove(player.getUUID());
        if (request != null) processSecretRequest(player, request);
    }

    public void onVersionPacket(ServerPlayer player, VersionPacket packet) {
        if (!versionReported.add(player.getUUID())) return;
        String serverVersion = CommonCompatibilityManager.INSTANCE.getModVersion();
        NetManager.sendToClient(player, new VersionPacket(serverVersion, Voicechat.COMPATIBILITY_VERSION));
        if (!VoiceVersion.matches(packet.getVersion(), packet.getProtocol(), serverVersion, Voicechat.COMPATIBILITY_VERSION)) {
            String message = VoiceVersion.mismatch(packet.getVersion(), packet.getProtocol(), serverVersion, Voicechat.COMPATIBILITY_VERSION);
            Voicechat.LOGGER.warn("Player {} ({}): {}", player.getName().getString(), player.getUUID(), message);
        }
    }

    public boolean isCompatible(ServerPlayer player) {
        return isCompatible(player.getUUID());
    }

    private boolean supportsVersion(int version) {
        return TransportMode.parse(Voicechat.SERVER_CONFIG.getTransportMode()).supports(VoiceProtocol.fromVersion(version));
    }

    public VoiceProtocol getProtocol(UUID playerUuid) {
        return VoiceProtocol.fromVersion(clientCompatibilities.getOrDefault(playerUuid, -1));
    }

    public boolean isCompatible(UUID playerUuid) {
        return supportsVersion(clientCompatibilities.getOrDefault(playerUuid, -1));
    }

    public void serverStarting(MinecraftServer mcServer) {
        if (server != null) {
            server.close();
            server = null;
        }

        if (!CrossSideManager.get().shouldRunVoiceChatServer(mcServer)) {
            Voicechat.LOGGER.info("Disabling voice chat in singleplayer");
            return;
        }

        try {
            server = new Server(mcServer);
            server.start();
            PluginManager.instance().onServerStarted();
        } catch (Exception e) {
            Voicechat.LOGGER.error("Failed to start voice chat server", e);
        }
    }

    public void initializePlayerConnection(ServerPlayer player) {
        if (server == null) {
            return;
        }
        CommonCompatibilityManager.INSTANCE.emitPlayerCompatibilityCheckSucceeded(player);

        Secret secret = server.generateNewSecret(player.getUUID());
        if (secret == null) {
            Voicechat.LOGGER.warn("Player already requested secret - ignoring");
            return;
        }

        // TODO Add back when they re-add P2P
        // Only does something if hosting P2P
        // WebRtcUtils.addUncheckedRtcConnection(server, player);

        boolean dedicated = CommonCompatibilityManager.INSTANCE.isDedicatedServer();
        String configuredVoiceHost = dedicated ? Voicechat.SERVER_CONFIG.voiceHost.get() : "";
        String voiceHost = PluginManager.instance().getVoiceHost(player, configuredVoiceHost);
        if (!dedicated && !voiceHost.isEmpty()) {
            Voicechat.LOGGER.warn("A plugin modified voice_host on a non-dedicated server - Ignore this message if this is intended");
        }

        int advertisedPort = Voicechat.SERVER_CONFIG.isProxyMode() ? -1 : server.getPort();
        NetManager.sendToClient(player, new SecretPacket(player, secret, advertisedPort, Voicechat.SERVER_CONFIG, voiceHost,
                getProtocol(player.getUUID()).version()).withTransportExtension(extendedClients.contains(player.getUUID())));

        Voicechat.LOGGER.info("Sent secret to {}", player.getName().getString());
    }

    public void playerLoggedIn(ServerPlayer serverPlayer) {
        if (server != null && Voicechat.SERVER_CONFIG.isProxyMode()) requestProxySettings(serverPlayer);
        if (server != null) {
            server.onPlayerLoggedIn(serverPlayer);
        }

        if (!Voicechat.SERVER_CONFIG.forceVoiceChat.get()) {
            return;
        }

        Timer timer = new Timer("%s-login-timer".formatted(serverPlayer.getGameProfile().name()), true);
        MinecraftServer loginServer = serverPlayer.level().getServer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                timer.cancel();
                timer.purge();
                if (loginServer.isRunning()) {
                    CommonCompatibilityManager.INSTANCE.executeForPlayer(loginServer, serverPlayer, () -> {
                        if (!serverPlayer.connection.isAcceptingMessages() || isCompatible(serverPlayer)) return;
                        serverPlayer.connection.disconnect(
                                Component.literal(Voicechat.TRANSLATIONS.forceVoicechatKickMessage.get().formatted(
                                        CommonCompatibilityManager.INSTANCE.getModName(),
                                        CommonCompatibilityManager.INSTANCE.getModVersion()
                                )));
                    }, () -> {});
                }
            }
        }, Voicechat.SERVER_CONFIG.loginTimeout.get());
    }

    public void playerLoggedOut(ServerPlayer player) {
        proxyConfigSync.remove(player.getUUID());
        pendingProxyRequests.remove(player.getUUID());
        clientCompatibilities.remove(player.getUUID());
        extendedClients.remove(player.getUUID());
        versionReported.remove(player.getUUID());
        versionWarnings.remove(player.getUUID());
        rateLimiter.onPlayerLoggedOut(player);
        if (server == null) {
            return;
        }

        server.onPlayerLoggedOut(player);
        Voicechat.LOGGER.info("Disconnecting client {}", player.getName().getString());
    }

    public void onPlayerHide(ServerPlayer visibilityChangedPlayer, ServerPlayer observingPlayer) {
        if (server == null) {
            return;
        }

        server.onPlayerHide(visibilityChangedPlayer, observingPlayer);
    }

    public void onPlayerShow(ServerPlayer visibilityChangedPlayer, ServerPlayer observingPlayer) {
        if (server == null) {
            return;
        }

        server.onPlayerShow(visibilityChangedPlayer, observingPlayer);
    }

    public void serverVoiceChatConnected(ServerPlayer serverPlayer) {
        if (server == null) {
            return;
        }

        server.onPlayerVoicechatConnect(serverPlayer);
    }

    public void serverVoiceChatDisconnected(UUID uuid) {
        if (server == null) {
            return;
        }

        server.onPlayerVoicechatDisconnect(uuid);
    }

    public void playerCompatibilityCheckSucceeded(ServerPlayer player) {
        if (server == null) {
            return;
        }

        server.onPlayerCompatibilityCheckSucceeded(player);
    }

    public PacketRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    @Nullable
    public Server getServer() {
        return server;
    }

    public void serverStopping(MinecraftServer mcServer) {
        if (server != null) {
            server.close();
            server = null;
        }
    }

}
