package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.voice.transport.VoiceProtocol;
import de.maxhenkel.voicechat.voice.transport.TransportMode;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.compatibility.PlayerHideEvent;
import de.maxhenkel.voicechat.compatibility.PlayerShowEvent;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.legacy.LegacyCompatibility;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PacketRateLimiter;
import de.maxhenkel.voicechat.net.RequestSecretPacket;
import de.maxhenkel.voicechat.net.SecretPacket;
import de.maxhenkel.voicechat.net.VersionPacket;
import de.maxhenkel.voicechat.voice.transport.VoiceVersion;
import de.maxhenkel.voicechat.plugins.PluginManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerVoiceEvents implements Listener {

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
    }

    public void init() {
        if (server != null) {
            return;
        }
        if (!Bukkit.getOnlineMode()) {
            Voicechat.LOGGER.warn("Running in offline mode - Voice chat encryption is not secure!");
        }

        Voicechat.compatibility.registerPlayerHideEvent(this::onPlayerHide);
        Voicechat.compatibility.registerPlayerShowEvent(this::onPlayerShow);

        server = new Server();
        server.start();

        PluginManager.instance().onServerStarted();
    }

    public void onRequestSecretPacket(Player player, RequestSecretPacket packet) {
        Voicechat.LOGGER.info("Received secret request of {} ({})", player.getName(), packet.getCompatibilityVersion());
        UUID playerUUID;

        try {
            playerUUID = player.getUniqueId();
        } catch (UnsupportedOperationException e) {
            player.kickPlayer("Tried to authenticate voice chat while still connecting");
            Voicechat.LOGGER.warn("{} tried to authenticate voice chat while still connecting", player.getName());
            return;
        }

        VoiceProtocol selected = TransportMode.parse(Voicechat.SERVER_CONFIG.transportMode.get())
                .select(Voicechat.SERVER_CONFIG.transportPreference.get(), packet.getTransportCapabilities());
        if (selected == null) {
            Voicechat.LOGGER.warn("Client {} supports no enabled voice transport", player.getName());
            sendIncompatibleMessage(player, packet.getCompatibilityVersion());
            return;
        }
        Integer previousCompatibility = clientCompatibilities.putIfAbsent(playerUUID, packet.getCompatibilityVersion());
        VoiceProtocol previousProtocol = clientProtocols.putIfAbsent(playerUUID, selected);
        if ((previousCompatibility != null && previousCompatibility != packet.getCompatibilityVersion())
                || (previousProtocol != null && previousProtocol != selected)) return;
        if (packet.hasTransportExtension()) extendedClients.add(playerUUID);
        else extendedClients.remove(playerUUID);
        if (!isCompatible(player)) {
            Voicechat.LOGGER.warn("Connected client {} has incompatible voice chat version (server={}, client={})", player.getName(), Voicechat.COMPATIBILITY_VERSION, packet.getCompatibilityVersion());
            sendIncompatibleMessage(player, packet.getCompatibilityVersion());
        } else {
            initializePlayerConnection(player);
        }
    }

    public void onVersionPacket(Player player, VersionPacket packet) {
        if (!versionReported.add(player.getUniqueId())) return;
        String serverVersion = Voicechat.INSTANCE.getDescription().getVersion();
        NetManager.sendToClient(player, new VersionPacket(serverVersion, Voicechat.COMPATIBILITY_VERSION));
        if (!VoiceVersion.matches(packet.getVersion(), packet.getProtocol(), serverVersion, Voicechat.COMPATIBILITY_VERSION)) {
            String message = VoiceVersion.mismatch(packet.getVersion(), packet.getProtocol(), serverVersion, Voicechat.COMPATIBILITY_VERSION);
            Voicechat.LOGGER.warn("Player {} ({}): {}", player.getName(), player.getUniqueId(), message);
        }
    }

    public boolean isCompatible(Player player) {
        return isCompatible(player.getUniqueId());
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

    public static void sendIncompatibleMessage(Player player, int clientCompatibilityVersion) {
        if (clientCompatibilityVersion <= 6) {
            // Send a literal string, as we don't know if the translations exist on these versions
            player.sendMessage(String.format(Voicechat.TRANSLATIONS.voicechatNotCompatibleMessage.get(), BuildConstants.MOD_COMPATIBLE_VERSION, BuildConstants.PLUGIN_NAME));
        } else {
            // This translation key is only available for compatibility version 7+
            Voicechat.compatibility.sendIncompatibleMessage(player, BuildConstants.MOD_COMPATIBLE_VERSION, BuildConstants.PLUGIN_NAME);
        }
    }

    public void initializePlayerConnection(Player player) {
        if (server == null) {
            return;
        }
        ConnectionSecret connectionSecret = server.getConnectionSecrets().create(player.getUniqueId());
        if (connectionSecret == null) {
            if (Voicechat.debugMode()) {
                Voicechat.LOGGER.warn("Player {} already requested secret - ignoring", player.getName());
            } else {
                CooldownTimer.run(String.format("already_requested_secret_%s", player.getUniqueId()), () -> {
                    Voicechat.LOGGER.warn("Player {} already requested secret - ignoring", player.getName());
                });
            }
            return;
        }

        server.getPlayerStateManager().onPlayerCompatibilityCheckSucceeded(player);
        server.getCategoryManager().onPlayerCompatibilityCheckSucceeded(player);
        server.getGroupManager().onPlayerCompatibilityCheckSucceeded(player);

        String voiceHost = PluginManager.instance().getVoiceHost(player, Voicechat.SERVER_CONFIG.voiceHost.get());
        VoiceProtocol protocol = clientProtocols.get(player.getUniqueId());
        if (protocol == null) return;
        NetManager.sendToClient(player, new SecretPacket(player, connectionSecret.getSecret(), connectionSecret.getConnectionId(), server.getPort(), protocol.version(), Voicechat.SERVER_CONFIG, voiceHost)
                .withTransportExtension(extendedClients.contains(player.getUniqueId()))
                .withConnectionIdExtension(clientCompatibilities.getOrDefault(player.getUniqueId(), -1) >= 21
                        && clientCompatibilities.getOrDefault(player.getUniqueId(), -1) < VoiceProtocol.TCP.version()));
        Voicechat.LOGGER.info("Sent secret to {}", player.getName());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        server.getPlayerStateManager().onPlayerJoin(event);

        if (!Voicechat.SERVER_CONFIG.forceVoiceChat.get()) {
            return;
        }
        Player player = event.getPlayer();

        Voicechat.compatibility.runTaskLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            if (!isCompatible(player)) {
                player.kickPlayer(String.format(
                        Voicechat.TRANSLATIONS.forceVoicechatKickMessage.get(),
                        BuildConstants.PLUGIN_NAME,
                        Voicechat.INSTANCE.getDescription().getVersion()
                ));
            }
        }, Voicechat.SERVER_CONFIG.loginTimeout.get() / 50L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        server.getPlayerStateManager().onPlayerQuit(event);
        server.getGroupManager().onPlayerQuit(event);

        clientCompatibilities.remove(event.getPlayer().getUniqueId());
        clientProtocols.remove(event.getPlayer().getUniqueId());
        extendedClients.remove(event.getPlayer().getUniqueId());
        versionReported.remove(event.getPlayer().getUniqueId());
        versionWarnings.remove(event.getPlayer().getUniqueId());
        rateLimiter.onPlayerLoggedOut(event.getPlayer());
        if (server == null) {
            return;
        }

        server.disconnectClient(event.getPlayer().getUniqueId());
        server.getPacketRateLimiter().onPlayerLoggedOut(event.getPlayer());
        Voicechat.LOGGER.info("Disconnecting client {}", event.getPlayer().getName());
    }

    public void onPlayerHide(PlayerHideEvent event) {
        server.getPlayerStateManager().onPlayerHide(event);
    }

    public void onPlayerShow(PlayerShowEvent event) {
        server.getPlayerStateManager().onPlayerShow(event);
    }

    public PacketRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    public Server getServer() {
        return server;
    }
}
