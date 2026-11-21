package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.legacy.LegacyCompatibility;
import de.maxhenkel.voicechat.net.PacketRateLimiter;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.plugins.impl.audiosender.AudioSenderImpl;
import de.maxhenkel.voicechat.voice.common.*;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.ProxyRoutingPacket;
import de.maxhenkel.voicechat.net.ProxyAudioPacket;
import de.maxhenkel.voicechat.voice.transport.ProxyMessages;
import de.maxhenkel.voicechat.voice.transport.VoiceAvailability;
import de.maxhenkel.voicechat.voice.transport.VoiceProtocol;
import de.maxhenkel.voicechat.voice.transport.TransportMode;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server extends Thread {

    private static final String NONCE_MAC = "HmacSHA256";
    private static final int NONCE_KEY_SIZE_BYTES = 32;
    private static final long PERMISSION_CHECK_INTERVAL = 500L;
    private static final int MAX_PACKETS_PER_SECOND = 100;

    private final Map<UUID, ClientConnection> connections;
    private final ConnectionSecrets connectionSecrets;
    private final SecretKeySpec nonceKey;
    private final boolean dedicated;
    private int port;
    private final MinecraftServer server;
    private volatile VoicechatSocket socket;
    private volatile boolean running = true;
    private final ProcessThread processThread;
    private final BlockingQueue<UdpPacket> packetQueue;
    private final PingManager pingManager;
    private final PlayerStateManager playerStateManager;
    private final ServerGroupManager groupManager;
    private final ServerCategoryManager categoryManager;
    private final PacketRateLimiter packetRateLimiter;

    public Server(MinecraftServer server) {
        dedicated = server instanceof DedicatedServer;
        if (dedicated) Voicechat.SERVER_CONFIG.detectProxy(CommonCompatibilityManager.INSTANCE::isModLoaded);
        if (dedicated) {
            if (!server.usesAuthentication()) {
                Voicechat.LOGGER.warn("Running in offline mode - Voice chat encryption is not secure!");
            }
            if (CommonCompatibilityManager.INSTANCE.isFolia() && !Voicechat.SERVER_CONFIG.isProxyMode()) {
                throw new IllegalArgumentException("Folia requires proxy_mode=true with the matching Paper backend and voice proxy.");
            }
            int configPort = Voicechat.SERVER_CONFIG.voiceChatPort.get();
            if (configPort == 0) throw new IllegalArgumentException("Voice chat port=0 is forbidden; configure a fixed TCP port (1..65535).");
            if (configPort < 0) {
                Voicechat.LOGGER.warn("The TCP voice chat cannot share the Minecraft port; using voice port 24454 for legacy port=-1");
                port = 24454;
            } else {
                port = configPort;
            }
            if (!Voicechat.SERVER_CONFIG.isProxyMode() && TransportMode.parse(Voicechat.SERVER_CONFIG.transportMode.get()).tcp() && port != 0 && port == server.getPort()) {
                throw new IllegalArgumentException("TCP voice chat needs a port different from the Minecraft server port. Change port in voicechat-server.properties.");
            }
        } else {
            port = Voicechat.SERVER_CONFIG.voiceChatPort.get();
            if (port == 0) throw new IllegalArgumentException("Voice chat port=0 is forbidden");
            if (port < 0) port = 24454;
        }
        this.server = server;
        socket = PluginManager.instance().getSocketImplementation(server);
        connections = new ConcurrentHashMap<>();
        connectionSecrets = new ConnectionSecrets();
        nonceKey = new SecretKeySpec(Utils.generateSecureRandomBytes(NONCE_KEY_SIZE_BYTES), NONCE_MAC);
        packetQueue = new LinkedBlockingQueue<>(Voicechat.SERVER_CONFIG.packetQueueSize.get());
        pingManager = new PingManager(this);
        playerStateManager = new PlayerStateManager(this);
        groupManager = new ServerGroupManager(this);
        categoryManager = new ServerCategoryManager(this);
        packetRateLimiter = new PacketRateLimiter(MAX_PACKETS_PER_SECOND);
        setDaemon(true);
        setName("VoiceChatServerThread");
        setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        processThread = new ProcessThread();
        lastProxyRoutingUpdate = 0L;
        proxyRoutingSequence = 0L;
        lastBandwidthMetricsRefresh = 0L;
        lastModerationMaintenance = 0L;
    }

    public void onPlayerLoggedIn(ServerPlayer player) {
        playerStateManager.onPlayerLoggedIn(player);
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        this.disconnectClient(player.getUUID());
        packetRateLimiter.onPlayerLoggedOut(player);
        playerStateManager.onPlayerLoggedOut(player);
        groupManager.onPlayerLoggedOut(player);
    }

    public void onPlayerHide(ServerPlayer visibilityChangedPlayer, ServerPlayer observingPlayer) {
        playerStateManager.onPlayerHide(visibilityChangedPlayer, observingPlayer);
    }

    public void onPlayerShow(ServerPlayer visibilityChangedPlayer, ServerPlayer observingPlayer) {
        playerStateManager.onPlayerShow(visibilityChangedPlayer, observingPlayer);
    }

    public void onPlayerVoicechatConnect(ServerPlayer player) {
        playerStateManager.onPlayerVoicechatConnect(player);
    }

    public void onPlayerVoicechatDisconnect(UUID uuid) {
        playerStateManager.onPlayerVoicechatDisconnect(uuid);
    }

    public void onPlayerCompatibilityCheckSucceeded(ServerPlayer player) {
        playerStateManager.onPlayerCompatibilityCheckSucceeded(player);
        groupManager.onPlayerCompatibilityCheckSucceeded(player);
        categoryManager.onPlayerCompatibilityCheckSucceeded(player);
    }

    @Override
    public void run() {
        try {
            if (!running) {
                return;
            }
            processThread.start();
            if (Voicechat.SERVER_CONFIG.isProxyMode()) {
                while (running) {
                    Thread.sleep(1000L);
                }
                return;
            }

            String bindAddress = getBindAddress();
            try {
                InetAddress.getByName(bindAddress);
            } catch (UnknownHostException e) {
                Voicechat.LOGGER.error("Failed to parse bind IP address '{}'", bindAddress, e);
                Voicechat.LOGGER.info("Binding to wildcard IP address");
                bindAddress = "";
            }
            socket.open(port, bindAddress);
            if (!running) {
                return;
            }
            if (bindAddress.isEmpty()) {
                Voicechat.LOGGER.info("Voice chat server started at port {}", socket.getLocalPort());
            } else {
                Voicechat.LOGGER.info("Voice chat server started at {}:{}", bindAddress, socket.getLocalPort());
            }

            while (running && !socket.isClosed()) {
                try {
                    queuePacket(socket.read());
                } catch (ProtocolException e) {
                    if (Voicechat.debugMode()) {
                        Voicechat.LOGGER.warn("Failed to read packet", e);
                    } else {
                        CooldownTimer.run("socket_protocol_error", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet: {}", e.getMessage());
                        });
                    }
                } catch (Exception e) {
                    // Only log an error if the error isn't caused by the socket being closed
                    if (!(e instanceof SocketException && e.getCause() instanceof AsynchronousCloseException)) {
                        if (Voicechat.debugMode()) {
                            Voicechat.LOGGER.error("Failed to read from socket", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Voicechat.LOGGER.error("Voice chat server error", e);
        } finally {
            running = false;
            socket.close();
            processThread.close();
            packetQueue.clear();
        }
    }

    private String getBindAddress() {
        if (!dedicated) {
            return "";
        }

        String bindAddress = Voicechat.SERVER_CONFIG.voiceChatBindAddress.get();

        if (bindAddress.trim().equals("*")) {
            bindAddress = "";
        } else if (bindAddress.trim().equals("")) {
            if (server instanceof DedicatedServer) {
                bindAddress = ((DedicatedServer) server).getProperties().serverIp;
                if (!bindAddress.trim().isEmpty()) {
                    try {
                        InetAddress address = InetAddress.getByName(bindAddress);
                        if (address.isLoopbackAddress()) {
                            bindAddress = "";
                        } else {
                            Voicechat.LOGGER.info("Using server-ip as bind address: {}", bindAddress);
                        }
                    } catch (Exception e) {
                        Voicechat.LOGGER.warn("Invalid server-ip", e);
                        bindAddress = "";
                    }
                }
            }
        }
        return bindAddress;
    }

    /**
     * Changes the port of the voice chat server.
     * <b>NOTE:</b> This removes every existing connection and all secrets!
     *
     * @param port the new voice chat port
     * @throws Exception if an error opening the socket on the new port occurs
     */
    public void changePort(int port) throws Exception {
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Voice chat requires a fixed port (1..65535)");
        if (!running) {
            throw new IllegalStateException("Voice chat server is closed");
        }
        if (!Voicechat.SERVER_CONFIG.isProxyMode() && TransportMode.parse(Voicechat.SERVER_CONFIG.transportMode.get()).tcp() && port != 0 && port == server.getPort()) {
            throw new IllegalArgumentException("TCP voice chat cannot share the Minecraft server port");
        }
        VoicechatSocket newSocket = PluginManager.instance().getSocketImplementation(server);
        if (!Voicechat.SERVER_CONFIG.isProxyMode()) newSocket.open(port, getBindAddress());
        VoicechatSocket old = socket;
        socket = newSocket;
        this.port = port;
        old.close();
        connections.clear();
        connectionSecrets.clear();
    }

    public ConnectionSecrets getConnectionSecrets() {
        return connectionSecrets;
    }

    /**
     * Creates a nonce that only this server can create for the given connection and address.
     * The server sends it to the address in the {@link AuthenticateAckPacket} and only accepts a {@link ConnectionCheckPacket} with it from the same address.
     * This way, a client can only connect from an address it can receive packets at.
     *
     * @param connectionId the connection ID of the player
     * @param address      the address of the client
     * @return the nonce
     */
    public byte[] createNonce(UUID connectionId, SocketAddress address) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(NONCE_MAC);
        mac.init(nonceKey);
        mac.update(ByteBuffer.allocate(16).putLong(connectionId.getMostSignificantBits()).putLong(connectionId.getLeastSignificantBits()).array());
        mac.update(addressToBytes(address));
        return Arrays.copyOf(mac.doFinal(), NetworkMessage.NONCE_SIZE_BYTES);
    }

    private static byte[] addressToBytes(SocketAddress address) {
        if (!(address instanceof InetSocketAddress)) {
            return address.toString().getBytes(StandardCharsets.UTF_8);
        }
        InetSocketAddress inetAddress = (InetSocketAddress) address;
        byte[] ip = inetAddress.getAddress().getAddress();
        return ByteBuffer.allocate(ip.length + 4).put(ip).putInt(inetAddress.getPort()).array();
    }

    public void disconnectClient(UUID playerUUID) {
        connections.remove(playerUUID);
        connectionSecrets.remove(playerUUID);
        PluginManager.instance().onPlayerDisconnected(playerUUID);
    }

    private void closeConnection(@Nullable ClientConnection connection) {
        if (connection != null) {
            socket.closeConnection(connection.getAddress());
        }
    }

    public void close() {
        running = false;
        socket.close();
        processThread.close();

        PluginManager.instance().onServerStopped();
    }

    public boolean isClosed() {
        return !running;
    }

    private class ProcessThread extends Thread {
        private volatile boolean running;
        private long lastKeepAlive;
        private long lastPermissionCheck;

        public ProcessThread() {
            running = true;
            lastKeepAlive = Utils.getMilliseconds();
            lastPermissionCheck = Utils.getMilliseconds();
            setDaemon(true);
            setName("VoiceChatPacketProcessingThread");
            setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        }

        @Override
        public void run() {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastBandwidthMetricsRefresh >= 1000L) {
                        lastBandwidthMetricsRefresh = now;
                        protectionManager.refreshServerMetrics();
                    }
                    if (now - lastModerationMaintenance >= 60_000L) {
                        lastModerationMaintenance = now;
                        moderationCache.enqueuePeriodic();
                        moderationCache.cleanup();
                    }
                    if (now - lastProxyRoutingUpdate >= 250L && routingUpdateQueued.compareAndSet(false, true)) {
                        lastProxyRoutingUpdate = now;
                        scheduleProxyRoutingUpdate();
                    }
                    pingManager.checkTimeouts();
                    long time = Utils.getMilliseconds();
                    if (time - lastKeepAlive > Voicechat.SERVER_CONFIG.keepAlive.get()) {
                        sendKeepAlives();
                        lastKeepAlive = time;
                    }
                    if (time - lastPermissionCheck > PERMISSION_CHECK_INTERVAL) {
                        // Permission checks are not guaranteed to be thread-safe
                        CommonCompatibilityManager.INSTANCE.execute(server, Server.this::pollPermissions);
                        lastPermissionCheck = time;
                    }

                    UdpPacket udpPacket = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (udpPacket == null) {
                        continue;
                    }

                    NetworkMessage message;
                    try {
                        message = NetworkMessage.readPacketServer(LegacyCompatibility.translateIncomingVoicePacket(Server.this, udpPacket), Server.this);
                    } catch (Exception e) {
                        CooldownTimer.run("failed_reading_packet", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet from {}", udpPacket.getSocketAddress());
                        });
                        continue;
                    }

                    if (message == null) {
                        continue;
                    }

                    if (Utils.getMilliseconds() - message.getTimestamp() > message.getTTL()) {
                        CooldownTimer.run("ttl", () -> {
                            Voicechat.LOGGER.warn("Dropping voice chat packets! Your Server might be overloaded!");
                            Voicechat.LOGGER.warn("Packet queue has {} packets", packetQueue.size());
                        });
                        continue;
                    }

                    ConnectionSecret connectionSecret = connectionSecrets.getByConnectionId(message.getConnectionId());
                    if (connectionSecret == null) {
                        continue;
                    }

                    if (message.getPacket() instanceof AuthenticatePacket) {
                        // The connection is only created once the client sends the nonce back from the same address
                        sendPacket(new AuthenticateAckPacket(createNonce(connectionSecret.getConnectionId(), message.getAddress())), new ClientConnection(connectionSecret, message.getAddress()));
                        if (!connectionSecret.isAuthenticated()) {
                            connectionSecret.setAuthenticated(true);
                            Voicechat.LOGGER.info("Successfully authenticated player {}", connectionSecret.getPlayerUUID());
                        }
                        pendingAuthentications.entrySet().removeIf(e -> e.getValue().getHandshake().expired());
                        ClientConnection pending = pendingAuthentications.get(message.getAddress());
                        if (pending == null) {
                            if (pendingAuthentications.size() >= 1024) { socket.closeConnection(message.getAddress()); continue; }
                            pending = new ClientConnection(packet.getPlayerUUID(), message.getAddress());
                            pending.beginAuthentication(secret.getSecret());
                            pendingAuthentications.put(message.getAddress(), pending);
                        }
                        if (!pending.getPlayerUUID().equals(packet.getPlayerUUID())) { socket.closeConnection(message.getAddress()); continue; }
                        sendPacket(new AuthenticationChallengePacket(pending.getHandshake().challenge()), pending);
                        continue;
                    }
                    if (message.getPacket() instanceof AuthenticationResponsePacket packet) {
                        ClientConnection pending = pendingAuthentications.get(message.getAddress());
                        if (pending == null || !pending.getPlayerUUID().equals(message.getSenderUUID())) continue;
                        if (!pending.authenticate(packet.getData())) {
                            pendingAuthentications.remove(message.getAddress());
                            socket.closeConnection(message.getAddress());
                            continue;
                        }
                        pendingAuthentications.remove(message.getAddress());
                        ClientConnection old = connections.remove(pending.getPlayerUUID());
                        if (old != null) socket.closeConnection(old.getAddress());
                        old = unCheckedConnections.put(pending.getPlayerUUID(), pending);
                        if (old != null) socket.closeConnection(old.getAddress());
                        lastAudioSequences.remove(pending.getPlayerUUID());
                        availabilityStates.remove(pending.getPlayerUUID());
                        sendPacket(new AuthenticateAckPacket(), pending);
                        continue;
                    }

                    if (message.getPacket() instanceof ConnectionCheckPacket packet) {
                        if (!MessageDigest.isEqual(createNonce(connectionSecret.getConnectionId(), message.getAddress()), packet.getNonce())) {
                            continue;
                        }
                        ClientConnection connection = connections.get(connectionSecret.getPlayerUUID());
                        if (connection != null) {
                            if (connection.getAddress().equals(message.getAddress())) {
                                sendPacket(new ConnectionCheckAckPacket(), connection);
                            }
                            continue;
                        }
                        connection = new ClientConnection(connectionSecret, message.getAddress());
                        connections.put(connection.getPlayerUUID(), connection);
                        Voicechat.LOGGER.info("Successfully validated connection of player {}", connection.getPlayerUUID());
                        ServerPlayer player = server.getPlayerList().getPlayer(connection.getPlayerUUID());
                        if (player != null) {
                            try {
                                CommonCompatibilityManager.INSTANCE.emitServerVoiceChatConnectedEvent(player);
                                PluginManager.instance().onPlayerConnected(player);
                                playerStateManager.evaluateForcedState(player);
                            } finally {
                                connection.setInitialized(true);
                            }
                            Voicechat.LOGGER.info("Player {} ({}) successfully connected to voice chat", player.getName().getString(), connection.getPlayerUUID());
                        }
                        sendPacket(new ConnectionCheckAckPacket(), connection);
                        continue;
                    }

                    ClientConnection conn = getSender(message);
                    if (conn == null) {
                        continue;
                    }

                    if (!packetRateLimiter.allow(conn.getPlayerUUID())) {
                        // Remove the connection and remove the secret, so the player can't continue spamming until the kick is processed
                        connections.remove(conn.getPlayerUUID());
                        connectionSecrets.remove(conn.getPlayerUUID());
                        CommonCompatibilityManager.INSTANCE.execute(server, () -> kickForExceedingRateLimit(conn.getPlayerUUID()));
                        continue;
                    }

                    if (message.getPacket() instanceof MicPacket packet) {
                        if (acceptAudioSequence(conn.getPlayerUUID(), packet)) onMicPacket(conn.getPlayerUUID(), packet);
                    } else if (message.getPacket() instanceof PingPacket packet) {
                        pingManager.onPongPacket(packet);
                    } else if (message.getPacket() instanceof KeepAlivePacket) {
                        conn.setLastKeepAliveResponse(Utils.getMilliseconds());
                    }
                } catch (Exception e) {
                    Voicechat.LOGGER.error("Voice chat server error", e);
                }
            }
        }

        public void close() {
            running = false;
        }
    }

    private void kickForExceedingRateLimit(UUID playerUuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        Voicechat.LOGGER.warn("Player {} exceeded voice chat packet rate limit", player.getName().getString());
        player.connection.disconnect(Component.translatableWithFallback("disconnect.exceeded_packet_rate", "Kicked for exceeding packet rate limit"));
    }

    public void onMicPacket(UUID playerUuid, MicPacket packet) {
        // Folia routes immutable snapshots on the proxy; backend microphone hooks are unsupported.
        if (Voicechat.SERVER_CONFIG.isProxyMode() && CommonCompatibilityManager.INSTANCE.isFolia()) {
            byte[] audio = packet.getData();
            protectionManager.recordInbound(playerUuid, audio == null ? 0 : audio.length);
            if (audio == null || audio.length == 0) {
                protectionManager.release(playerUuid);
            } else if (protectionManager.tryAdmit(playerUuid)) {
                moderationCache.record(playerUuid, packet);
            }
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        PlayerState state = playerStateManager.getState(player.getUUID());
        if (state == null || state.isForceMuted() || state.isForceDisabled()) {
            return;
        }
        moderationCache.record(playerUuid, packet);
        if (!PluginManager.instance().onMicPacket(player, state, packet)) {
            processMicPacket(player, state, packet);
        }
    }

    private void processMicPacket(ServerPlayer player, PlayerState state, MicPacket packet) {
        if (state.hasGroup()) {
            @Nullable Group group = groupManager.getGroup(state.getGroup());
            processGroupPacket(state, player, packet);
            if (group == null || group.isOpen()) {
                processProximityPacket(state, player, packet);
            }
            return;
        }
        processProximityPacket(state, player, packet);
    }

    private void processGroupPacket(PlayerState senderState, ServerPlayer sender, MicPacket packet) {
        UUID groupId = senderState.getGroup();
        if (groupId == null) {
            return;
        }
        GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getUuid(), senderState.getUuid(), packet.getData(), packet.getSequenceNumber(), null);
        for (PlayerState state : playerStateManager.getStates()) {
            if (!groupId.equals(state.getGroup())) {
                continue;
            }
            if (senderState.getUuid().equals(state.getUuid())) {
                continue;
            }
            ServerPlayer p = server.getPlayerList().getPlayer(state.getUuid());
            if (p == null) {
                continue;
            }
            @Nullable ClientConnection connection = getConnection(state.getUuid());
            sendSoundPacket(sender, senderState, p, state, connection, groupSoundPacket, SoundPacketEvent.SOURCE_GROUP);
        }
    }

    private void processProximityPacket(PlayerState senderState, ServerPlayer sender, MicPacket packet) {
        @Nullable UUID groupId = senderState.getGroup();
        float distance;
        if (packet.isWhispering()) {
            distance = Voicechat.SERVER_CONFIG.whisperDistance.get().floatValue();
        } else {
            distance = Utils.getDefaultDistanceServer();
        }

        distance = PluginManager.instance().getDistance(sender, senderState, packet, distance);

        SoundPacket<?> soundPacket = null;
        String source = null;
        if (sender.isSpectator()) {
            if (Voicechat.SERVER_CONFIG.spectatorPlayerPossession.get()) {
                Entity camera = sender.getCamera();
                if (camera instanceof ServerPlayer spectatingPlayer) {
                    if (spectatingPlayer != sender) {
                        PlayerState receiverState = playerStateManager.getState(spectatingPlayer.getUUID());
                        if (receiverState == null) {
                            return;
                        }
                        GroupSoundPacket groupSoundPacket = new GroupSoundPacket(senderState.getUuid(), senderState.getUuid(), packet.getData(), packet.getSequenceNumber(), null);
                        @Nullable ClientConnection connection = getConnection(receiverState.getUuid());
                        sendSoundPacket(sender, senderState, spectatingPlayer, receiverState, connection, groupSoundPacket, SoundPacketEvent.SOURCE_SPECTATOR);
                        return;
                    }
                }
            }
            if (Voicechat.SERVER_CONFIG.spectatorInteraction.get()) {
                soundPacket = new LocationSoundPacket(sender.getUUID(), sender.getUUID(), sender.getEyePosition(), packet.getData(), packet.getSequenceNumber(), distance, null);
                source = SoundPacketEvent.SOURCE_SPECTATOR;
            }
        }

        if (soundPacket == null) {
            soundPacket = new PlayerSoundPacket(sender.getUUID(), sender.getUUID(), packet.getData(), packet.getSequenceNumber(), packet.isWhispering(), distance, null);
            source = SoundPacketEvent.SOURCE_PROXIMITY;
        }

        broadcast(ServerPlayerManager.getPlayersInRange(sender.level(), sender.position(), getBroadcastRange(distance), p -> !p.getUUID().equals(sender.getUUID())), soundPacket, sender, senderState, groupId, source);
    }

    public void sendSoundPacket(@Nullable ServerPlayer sender, @Nullable PlayerState senderState, ServerPlayer receiver, PlayerState receiverState, @Nullable ClientConnection connection, SoundPacket<?> soundPacket, String source) {
        if (soundPacket.getData().length > AudioUtils.MAX_OPUS_PAYLOAD_SIZE) {
            CooldownTimer.run("packet_data_too_large", () -> {
                Voicechat.LOGGER.warn("Tried to send a packet that's too large ({} bytes > {} bytes)", soundPacket.getData().length, AudioUtils.MAX_OPUS_PAYLOAD_SIZE);
            });
            return;
        }
        PluginManager.instance().onListenerAudio(receiver.getUUID(), soundPacket);

        if (connection == null) {
            return;
        }

        if (receiverState.isDisabledOrForceDisabled() || receiverState.isDisconnected()) {
            return;
        }

        if (PluginManager.instance().onSoundPacket(sender, senderState, receiver, receiverState, soundPacket, source)) {
            return;
        }

        sendPacket(soundPacket, connection);
    }

    public double getBroadcastRange(float minRange) {
        double broadcastRange = Voicechat.SERVER_CONFIG.broadcastRange.get();
        if (broadcastRange < 0D) {
            broadcastRange = Voicechat.SERVER_CONFIG.voiceChatDistance.get() + 1D;
        }
        return Math.max(broadcastRange, minRange);
    }

    public void broadcast(Collection<ServerPlayer> players, SoundPacket<?> packet, @Nullable ServerPlayer sender, @Nullable PlayerState senderState, @Nullable UUID groupId, String source) {
        for (ServerPlayer player : players) {
            PlayerState state = playerStateManager.getState(player.getUUID());
            if (state == null) {
                continue;
            }
            if (state.hasGroup() && state.getGroup().equals(groupId)) {
                continue;
            }
            @Nullable Group receiverGroup = null;
            if (state.hasGroup()) {
                receiverGroup = groupManager.getGroup(state.getGroup());
            }
            if (receiverGroup != null && receiverGroup.isIsolated()) {
                continue;
            }
            @Nullable ClientConnection connection = getConnection(state.getUuid());
            sendSoundPacket(sender, senderState, player, state, connection, packet, source);
        }
    }

    private void sendKeepAlives() {
        long timestamp = Utils.getMilliseconds();

        unCheckedConnections.values().removeIf(connection -> {
            if (timestamp - connection.getLastKeepAliveResponse() >= Voicechat.SERVER_CONFIG.keepAlive.get() * 10L) {
                closeConnection(connection);
                return true;
            }
            return false;
        });

        connections.values().removeIf(connection -> {
            if (timestamp - connection.getLastKeepAliveResponse() >= Voicechat.SERVER_CONFIG.keepAlive.get() * 10L) {
                // Don't call disconnectClient here!
                connectionSecrets.remove(connection.getPlayerUUID());
                Voicechat.LOGGER.info("Player {} timed out", connection.getPlayerUUID());
                ServerPlayer player = server.getPlayerList().getPlayer(connection.getPlayerUUID());
                if (player != null) {
                    Voicechat.LOGGER.info("Reconnecting player {}", player.getName().getString());
                    Voicechat.SERVER.initializePlayerConnection(player);
                } else {
                    Voicechat.LOGGER.warn("Reconnecting player {} failed (Could not find player)", connection.getPlayerUUID());
                }
                CommonCompatibilityManager.INSTANCE.emitServerVoiceChatDisconnectedEvent(connection.getPlayerUUID());
                PluginManager.instance().onPlayerDisconnected(connection.getPlayerUUID());
                return true;
            }
            return false;
        });

        for (ClientConnection connection : connections.values()) {
            sendPacket(new KeepAlivePacket(), connection);
        }

    }

    private void pollPermissions() {
        for (ClientConnection connection : connections.values()) {
            // The initial forced state evaluation needs to happen after the connected event
            if (connection.isInitialized()) {
                pollPermissions(connection.getPlayerUUID());
            }
        }
        for (UUID audioSenderPlayer : AudioSenderImpl.getRegisteredPlayers()) {
            pollPermissions(audioSenderPlayer);
        }
    }

    private void pollPermissions(UUID playerUuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player != null) {
            playerStateManager.evaluateForcedStateIfPermissionsChanged(player);
        }
    }

    public void evaluateForcedState(UUID playerUuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player != null) {
            playerStateManager.evaluateForcedState(player);
        }
    }

    @Nullable
    public ClientConnection getSender(NetworkMessage message) {
        ConnectionSecret connectionSecret = connectionSecrets.getByConnectionId(message.getConnectionId());
        if (connectionSecret == null) {
            return null;
        }
        ClientConnection connection = connections.get(connectionSecret.getPlayerUUID());
        if (connection == null || !connection.getAddress().equals(message.getAddress())) {
            return null;
        }
        return connection;
    }

    public Map<UUID, ClientConnection> getConnections() {
        return connections;
    }

    @Nullable
    public ClientConnection getConnection(UUID playerID) {
        return connections.get(playerID);
    }

    public VoicechatSocket getSocket() {
        return socket;
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /**
     * Sends the packet and handles potential errors.
     *
     * @param packet     the packet to send
     * @param connection the connection to send the packet to
     * @return if the packet was sent successfully
     */
    public boolean sendPacket(Packet<?> packet, ClientConnection connection) {
        try {
            sendPacketRaw(packet, connection);
            return true;
        } catch (Exception e) {
            Voicechat.LOGGER.error("Failed to send voice chat packet to {}", connection.getPlayerUUID());
            return false;
        }
    }

    /**
     * Sends the packet. You must handle potential errors
     *
     * @param packet     the packet to send
     * @param connection the connection to send the packet to
     * @throws Exception if an I/O error occurs
     */
    public void sendPacketRaw(Packet<?> packet, ClientConnection connection) throws Exception {
        if (packet instanceof SoundPacket<?> sound) protectionManager.recordOutbound(sound.getData().length);
        connection.send(this, new NetworkMessage(packet));
    }

    public void addRawPacket(RawUdpPacket rawUdpPacket) {
        queuePacket(rawUdpPacket);
    }

    private void queuePacket(RawUdpPacket rawUdpPacket) {
        if (!packetQueue.offer(new UdpPacket(rawUdpPacket.getData(), rawUdpPacket.getSocketAddress(), Utils.getMilliseconds()))) {
            CooldownTimer.run("packet_queue_full", () -> {
                Voicechat.LOGGER.warn("Voice chat packet queue is full ({} packets) - dropping packets", packetQueue.size());
            });
        }
    }

    public PingManager getPingManager() {
        return pingManager;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }

    public ServerGroupManager getGroupManager() {
        return groupManager;
    }

    public ServerCategoryManager getCategoryManager() {
        return categoryManager;
    }

    public VoiceProtectionManager getProtectionManager() {
        return protectionManager;
    }

    public VoiceModerationCache getModerationCache() {
        return moderationCache;
    }

    public MinecraftServer getServer() {
        return server;
    }
}
