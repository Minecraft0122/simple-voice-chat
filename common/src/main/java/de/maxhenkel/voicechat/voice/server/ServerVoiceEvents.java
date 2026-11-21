package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.voice.transport.VoiceProtocol;
import de.maxhenkel.voicechat.voice.transport.TransportMode;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.intercompatibility.CrossSideManager;
import de.maxhenkel.voicechat.legacy.LegacyCompatibility;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PacketRateLimiter;
import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.net.VersionPacket;
import de.maxhenkel.voicechat.voice.transport.VoiceVersion;
import de.maxhenkel.voicechat.plugins.PluginManager;
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
    private final Map<UUID, VoiceProtocol> clientProtocols = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> extendedClients = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> versionReported = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> versionWarnings = ConcurrentHashMap.newKeySet();
    private final PacketRateLimiter rateLimiter;
    private Server server;

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
            Voicechat.LOGGER.info("Received secret request of {} ({})", player.getName().getString(), packet.getCompatibilityVersion());
            VoiceProtocol selected = TransportMode.parse(Voicechat.SERVER_CONFIG.transportMode.get())
                    .select(Voicechat.SERVER_CONFIG.transportPreference.get(), packet.getTransportCapabilities());
            if (selected == null) {
                Voicechat.LOGGER.warn("Client {} supports no enabled voice transport", player.getName().getString());
                player.sendSystemMessage(getIncompatibleMessage(packet.getCompatibilityVersion()));
                return;
            }
            Integer previousCompatibility = clientCompatibilities.putIfAbsent(player.getUUID(), packet.getCompatibilityVersion());
            VoiceProtocol previousProtocol = clientProtocols.putIfAbsent(player.getUUID(), selected);
            if ((previousCompatibility != null && previousCompatibility != packet.getCompatibilityVersion())
                    || (previousProtocol != null && previousProtocol != selected)) return;
            if (packet.hasTransportExtension()) extendedClients.add(player.getUUID());
            else extendedClients.remove(player.getUUID());
            if (!isCompatible(player)) {
                Voicechat.LOGGER.warn("Connected client {} has incompatible voice chat version (server={}, client={})", player.getName().getString(), Voicechat.COMPATIBILITY_VERSION, packet.getCompatibilityVersion());
                player.sendSystemMessage(getIncompatibleMessage(packet.getCompatibilityVersion()));
            } else {
                initializePlayerConnection(player);
            }
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
        return TransportMode.parse(Voicechat.SERVER_CONFIG.transportMode.get()).supports(VoiceProtocol.fromVersion(version));
    }

    public VoiceProtocol getProtocol(UUID playerUuid) {
        return clientProtocols.get(playerUuid);
    }

    public boolean isCompatible(UUID playerUuid) {
        int compatibilityVersion = getCompatibilityVersion(playerUuid);
        return compatibilityVersion == Voicechat.COMPATIBILITY_VERSION
                || compatibilityVersion == VoiceProtocol.TCP.version()
                || LegacyCompatibility.isSupported(compatibilityVersion);
    }

    /**
     * @param playerUuid the player UUID
     * @return the compatibility version or -1 if not determined yet
     */
    public int getCompatibilityVersion(UUID playerUuid) {
        return clientCompatibilities.getOrDefault(playerUuid, -1);
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
        ConnectionSecret connectionSecret = server.getConnectionSecrets().create(player.getUUID());
        if (connectionSecret == null) {
            if (Voicechat.debugMode()) {
                Voicechat.LOGGER.warn("Player {} already requested secret - ignoring", player.getName().getString());
            } else {
                CooldownTimer.run(String.format("already_requested_secret_%s", player.getUUID()), () -> {
                    Voicechat.LOGGER.warn("Player {} already requested secret - ignoring", player.getName().getString());
                });
            }
            return;
        }

        CommonCompatibilityManager.INSTANCE.emitPlayerCompatibilityCheckSucceeded(player);

        // TODO Add back when they re-add P2P
        // Only does something if hosting P2P
        // WebRtcUtils.addUncheckedRtcConnection(server, player);

        boolean dedicated = CommonCompatibilityManager.INSTANCE.isDedicatedServer();
        String configuredVoiceHost = dedicated ? Voicechat.SERVER_CONFIG.voiceHost.get() : "";
        String voiceHost = PluginManager.instance().getVoiceHost(player, configuredVoiceHost);
        if (!dedicated && !voiceHost.isEmpty()) {
            Voicechat.LOGGER.warn("A plugin modified voice_host on a non-dedicated server - Ignore this message if this is intended");
        }

        VoiceProtocol protocol = clientProtocols.get(player.getUUID());
        if (protocol == null) return;
        NetManager.sendToClient(player, new SecretPacket(player, connectionSecret.getSecret(), connectionSecret.getConnectionId(), server.getPort(), protocol.version(), Voicechat.SERVER_CONFIG, voiceHost)
                .withTransportExtension(extendedClients.contains(player.getUUID()))
                .withConnectionIdExtension(clientCompatibilities.getOrDefault(player.getUUID(), -1) >= 21
                        && clientCompatibilities.getOrDefault(player.getUUID(), -1) < VoiceProtocol.TCP.version()));
        Voicechat.LOGGER.info("Sent secret to {}", player.getName().getString());
    }

    public void playerLoggedIn(ServerPlayer serverPlayer) {
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
        clientCompatibilities.remove(player.getUUID());
        clientProtocols.remove(player.getUUID());
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
