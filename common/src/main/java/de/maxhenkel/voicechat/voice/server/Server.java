package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.plugins.PluginManager;
import de.maxhenkel.voicechat.voice.common.*;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.ProxyRoutingPacket;
import de.maxhenkel.voicechat.net.ProxyAudioPacket;
import de.maxhenkel.voicechat.voice.transport.ProxyMessages;
import de.maxhenkel.voicechat.voice.transport.VoiceAvailability;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousCloseException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server extends Thread {

    private final Map<java.net.SocketAddress, ClientConnection> pendingAuthentications = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAudioSequences = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> availabilityStates = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean routingUpdateQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<UUID, ClientConnection> connections;
    private final Map<UUID, ClientConnection> unCheckedConnections;
    private final Map<UUID, Secret> secrets;
    private final boolean dedicated;
    private int port;
    private final MinecraftServer server;
    private volatile VoicechatSocket socket;
    private volatile boolean running = true;
    private final ProcessThread processThread;
    private final BlockingQueue<RawUdpPacket> packetQueue;
    private final PingManager pingManager;
    private final PlayerStateManager playerStateManager;
    private final ServerGroupManager groupManager;
    private final ServerCategoryManager categoryManager;
    private final Map<UUID, ProxyRoutingPacket> lastRoutingStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRoutingSent = new ConcurrentHashMap<>();
    private long lastProxyRoutingUpdate;
    private final long proxyRoutingGeneration = new SecureRandom().nextLong();
    private long proxyRoutingSequence;

    public Server(MinecraftServer server) {
        dedicated = server instanceof DedicatedServer;
        if (dedicated) {
            if (!server.usesAuthentication()) {
                Voicechat.LOGGER.warn("Running in offline mode - Voice chat encryption is not secure!");
            }
            int configPort = Voicechat.SERVER_CONFIG.voiceChatPort.get();
            if (configPort == 0) throw new IllegalArgumentException("Voice chat port=0 is forbidden; configure a fixed TCP port (1..65535).");
            if (configPort < 0) {
                Voicechat.LOGGER.warn("The TCP voice chat cannot share the Minecraft port; using voice port 24454 for legacy port=-1");
                port = 24454;
            } else {
                port = configPort;
            }
            if (!Voicechat.SERVER_CONFIG.proxyMode.get() && port != 0 && port == server.getPort()) {
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
        unCheckedConnections = new ConcurrentHashMap<>();
        secrets = new ConcurrentHashMap<>();
        packetQueue = new LinkedBlockingQueue<>(4096);
        pingManager = new PingManager(this);
        playerStateManager = new PlayerStateManager(this);
        groupManager = new ServerGroupManager(this);
        categoryManager = new ServerCategoryManager(this);
        setDaemon(true);
        setName("VoiceChatServerThread");
        setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        processThread = new ProcessThread();
        lastProxyRoutingUpdate = 0L;
        proxyRoutingSequence = 0L;
    }

    public void onPlayerLoggedIn(ServerPlayer player) {
        playerStateManager.onPlayerLoggedIn(player);
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        this.disconnectClient(player.getUUID());
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
            if (Voicechat.SERVER_CONFIG.proxyMode.get()) {
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
                    RawUdpPacket packet = socket.read();
                    if (!packetQueue.offer(packet)) {
                        socket.closeConnection(packet.getSocketAddress());
                        CooldownTimer.run("tcp_packet_queue_full", () -> Voicechat.LOGGER.warn("TCP voice packet queue is full; closing the sending connection"));
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
        if (!Voicechat.SERVER_CONFIG.proxyMode.get() && port != 0 && port == server.getPort()) {
            throw new IllegalArgumentException("TCP voice chat cannot share the Minecraft server port");
        }
        VoicechatSocket newSocket = PluginManager.instance().getSocketImplementation(server);
        newSocket.open(port, getBindAddress());
        VoicechatSocket old = socket;
        socket = newSocket;
        this.port = port;
        old.close();
        connections.clear();
        unCheckedConnections.clear();
        secrets.clear();
        packetQueue.clear();
    }

    public ClientConnection findVoiceSession(UUID uuid, java.net.SocketAddress address) {
        ClientConnection current = connections.get(uuid);
        if (current != null && current.getAddress().equals(address)) return current;
        current = unCheckedConnections.get(uuid);
        return current != null && current.getAddress().equals(address) ? current : null;
    }

    public Secret getSecret(UUID playerUUID) {
        if (hasSecret(playerUUID)) {
            return secrets.get(playerUUID);
        } else {
            Secret secret = Secret.generateNewRandomSecret();
            secrets.put(playerUUID, secret);
            return secret;
        }
    }

    /**
     * @param playerUUID the player uuid
     * @return the new secret or null if the player already has a secret
     */
    @Nullable
    public Secret generateNewSecret(UUID playerUUID) {
        if (hasSecret(playerUUID)) {
            return null;
        }
        return getSecret(playerUUID);
    }

    public boolean hasSecret(UUID playerUUID) {
        return secrets.containsKey(playerUUID);
    }

    public void disconnectClient(UUID playerUUID) {
        lastRoutingStates.remove(playerUUID);
        lastRoutingSent.remove(playerUUID);
        availabilityStates.remove(playerUUID);
        lastAudioSequences.remove(playerUUID);
        closeConnection(connections.remove(playerUUID));
        closeConnection(unCheckedConnections.remove(playerUUID));
        secrets.remove(playerUUID);
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

        public ProcessThread() {
            running = true;
            lastKeepAlive = 0L;
            setDaemon(true);
            setName("VoiceChatPacketProcessingThread");
            setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        }

        @Override
        public void run() {
            while (running) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastProxyRoutingUpdate >= 250L && routingUpdateQueued.compareAndSet(false, true)) {
                        lastProxyRoutingUpdate = now;
                        server.execute(() -> {
                            try { if (running) Server.this.sendProxyRoutingStates(); }
                            finally { routingUpdateQueued.set(false); }
                        });
                    }
                    pingManager.checkTimeouts();
                    if (Voicechat.SERVER_CONFIG.proxyMode.get()) {
                        Thread.sleep(50L);
                        continue;
                    }
                    long keepAliveTime = System.currentTimeMillis();
                    if (keepAliveTime - lastKeepAlive > Voicechat.SERVER_CONFIG.keepAlive.get()) {
                        sendKeepAlives();
                        lastKeepAlive = keepAliveTime;
                    }

                    RawUdpPacket rawPacket = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (rawPacket == null) {
                        continue;
                    }

                    NetworkMessage message;
                    try {
                        message = NetworkMessage.readPacketServer(rawPacket, Server.this);
                    } catch (Exception e) {
                        CooldownTimer.run("failed_reading_packet", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet from {}", rawPacket.getSocketAddress());
                        });
                        continue;
                    }

                    if (message == null) {
                        continue;
                    }

                    if (System.currentTimeMillis() - message.getTimestamp() > message.getTTL()) {
                        CooldownTimer.run("ttl", () -> {
                            Voicechat.LOGGER.warn("Dropping voice chat packets! Your Server might be overloaded!");
                            Voicechat.LOGGER.warn("Packet queue has {} packets", packetQueue.size());
                        });
                        continue;
                    }

                    if (message.getPacket() instanceof AuthenticatePacket packet) {
                        Secret secret = secrets.get(packet.getPlayerUUID());
                        if (secret == null || !secret.equals(packet.getSecret())) continue;
                        ClientConnection current = findVoiceSession(packet.getPlayerUUID(), message.getAddress());
                        if (current != null) { sendPacket(new AuthenticateAckPacket(), current); continue; }
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
                        if (pending == null) continue;
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

                    if (message.getPacket() instanceof ConnectionCheckPacket) {
                        ClientConnection connection = getUnconnectedSender(message);
                        if (connection == null) {
                            connection = getSender(message);
                            if (connection != null) {
                                sendPacket(new ConnectionCheckAckPacket(), connection);
                            }
                            continue;
                        }
                        // Refresh keepalive, so players who took longer than the timeout can still connect
                        connection.setLastKeepAliveResponse(System.currentTimeMillis());
                        connections.put(connection.getPlayerUUID(), connection);
                        unCheckedConnections.remove(connection.getPlayerUUID());
                        Voicechat.LOGGER.info("Successfully validated connection of player {}", connection.getPlayerUUID());
                        ServerPlayer player = server.getPlayerList().getPlayer(connection.getPlayerUUID());
                        if (player != null) {
                            CommonCompatibilityManager.INSTANCE.emitServerVoiceChatConnectedEvent(player);
                            PluginManager.instance().onPlayerConnected(player);
                            Voicechat.LOGGER.info("Player {} ({}) successfully connected to voice chat", player.getName().getString(), connection.getPlayerUUID());
                        }
                        sendPacket(new ConnectionCheckAckPacket(), connection);
                        continue;
                    }

                    ClientConnection conn = getSender(message);
                    if (conn == null) {
                        continue;
                    }

                    if (message.getPacket() instanceof MicPacket packet) {
                        if (acceptAudioSequence(conn.getPlayerUUID(), packet)) onMicPacket(conn.getPlayerUUID(), packet);
                    } else if (message.getPacket() instanceof PingPacket packet) {
                        pingManager.onPongPacket(packet);
                    } else if (message.getPacket() instanceof KeepAlivePacket) {
                        conn.setLastKeepAliveResponse(System.currentTimeMillis());
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

    private boolean acceptAudioSequence(UUID player, MicPacket packet) {
        long sequence = packet.getSequenceNumber();
        Long previous = lastAudioSequences.get(player);
        if (sequence < 0 || (previous != null && sequence <= previous)) return false;
        lastAudioSequences.put(player, sequence);
        return true;
    }

    private boolean spectatorBlocked(ServerPlayer player) {
        return !Voicechat.SERVER_CONFIG.allowSpectatorVoice.get() && (player.isSpectator());
    }

    private int voiceAvailability(ServerPlayer player) {
        if (spectatorBlocked(player)) return VoiceAvailability.SPECTATOR;
        if (!PermissionManager.INSTANCE.SPEAK_PERMISSION.hasPermission(player)) return VoiceAvailability.NO_PERMISSION;
        PlayerState state = playerStateManager.getState(player.getUUID());
        if (state == null) return VoiceAvailability.WAITING;
        if (state.isDisabled()) return VoiceAvailability.DISABLED;
        return VoiceAvailability.AVAILABLE;
    }

    private void sendProxyRoutingStates() {
        long now = System.currentTimeMillis();
        boolean proxy = Voicechat.SERVER_CONFIG.proxyMode.get();
        for (ServerPlayer sender : server.getPlayerList().getPlayers()) {
            UUID uuid = sender.getUUID();
            PlayerState state = playerStateManager.getState(uuid);
            if (state == null || !Voicechat.SERVER.isCompatible(sender)) continue;
            ClientConnection connection = connections.get(uuid);
            if (proxy && connection != null && now - connection.getLastKeepAliveResponse() > 3500L) {
                proxyDisconnected(uuid);
                connection = null;
            }
            int status = voiceAvailability(sender);
            if (!proxy) {
                if (connection != null && !java.util.Objects.equals(availabilityStates.put(uuid, status), status)) {
                    sendPacket(new AvailabilityPacket(status), connection);
                }
                continue;
            }
            boolean relay = PluginManager.instance().requiresBackendAudio() || Voicechat.SERVER_CONFIG.allowSpectatorVoice.get();
            boolean allowed = status == VoiceAvailability.AVAILABLE;
            List<UUID> normal = allowed && !relay ? getProxyTargets(sender, false) : List.of();
            List<UUID> whisper = allowed && !relay ? getProxyTargets(sender, true) : List.of();
            List<UUID> group = allowed && !relay ? getProxyGroupTargets(sender) : List.of();
            // Keep Minecraft plugin messages below the Bukkit payload limit.
            if (normal.size() + whisper.size() + group.size() > 1800) {
                relay = true; normal = List.of(); whisper = List.of(); group = List.of();
            }
            ProxyRoutingPacket packet = new ProxyRoutingPacket(uuid, proxyRoutingGeneration, ++proxyRoutingSequence,
                    status, relay, Voicechat.SERVER_CONFIG.voiceChatDistance.get().floatValue(),
                    Voicechat.SERVER_CONFIG.whisperDistance.get().floatValue(), normal, whisper, group);
            if (packet.sameRoutes(lastRoutingStates.get(uuid))) {
                if (now - lastRoutingSent.getOrDefault(uuid, 0L) < 1000L) continue;
                packet.heartbeat();
            } else {
                lastRoutingStates.put(uuid, packet);
            }
            lastRoutingSent.put(uuid, now);
            NetManager.sendToClient(sender, packet);
        }
    }

    /** Called only on the server thread, over the proxy-owned control channel. */
    public void handleProxyControl(ServerPlayer player, byte[] bytes) {
        if (!Voicechat.SERVER_CONFIG.proxyMode.get() || !Voicechat.SERVER.isCompatible(player)) return;
        try {
            ProxyMessages.Message message = ProxyMessages.decode(bytes);
            if (message.generation() != proxyRoutingGeneration) return;
            UUID uuid = player.getUUID();
            ClientConnection connection = connections.get(uuid);
            if (message.type() == ProxyMessages.CONNECTION) {
                if (message.payload().length != 1) return;
                if (message.payload()[0] == 0) {
                    if (connection != null && message.session().equals(connection.getProxySession())) proxyDisconnected(uuid);
                    return;
                }
                if (connection == null || !message.session().equals(connection.getProxySession())) {
                    if (connection != null) proxyDisconnected(uuid);
                    connection = new ClientConnection(uuid, new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 1));
                    connection.setProxySession(message.session());
                    connections.put(uuid, connection);
                    lastAudioSequences.remove(uuid);
                    CommonCompatibilityManager.INSTANCE.emitServerVoiceChatConnectedEvent(player);
                    PluginManager.instance().onPlayerConnected(player);
                }
                connection.setLastKeepAliveResponse(System.currentTimeMillis());
            } else if (message.type() == ProxyMessages.MICROPHONE && connection != null
                    && message.session().equals(connection.getProxySession()) && voiceAvailability(player) == VoiceAvailability.AVAILABLE) {
                NetworkMessage decoded = NetworkMessage.readPlaintext(connection.getAddress(), message.payload(), System.currentTimeMillis());
                if (decoded != null && decoded.getPacket() instanceof MicPacket mic) onMicPacket(uuid, mic);
            } else if (message.type() == ProxyMessages.PONG && connection != null
                    && message.session().equals(connection.getProxySession())) {
                NetworkMessage decoded = NetworkMessage.readPlaintext(connection.getAddress(), message.payload(), System.currentTimeMillis());
                if (decoded != null && decoded.getPacket() instanceof PingPacket pong) pingManager.onPongPacket(pong);
            }
        } catch (Exception e) {
            CooldownTimer.run("proxy-control", () -> Voicechat.LOGGER.warn("Invalid voice proxy control message", e));
        }
    }

    private void proxyDisconnected(UUID uuid) {
        if (connections.remove(uuid) == null) return;
        availabilityStates.remove(uuid);
        lastAudioSequences.remove(uuid);
        CommonCompatibilityManager.INSTANCE.emitServerVoiceChatDisconnectedEvent(uuid);
        PluginManager.instance().onPlayerDisconnected(uuid);
    }

    public void sendConnectionMessage(ClientConnection connection, NetworkMessage message) throws Exception {
        if (Voicechat.SERVER_CONFIG.proxyMode.get()) {
            if (connection.getProxySession() == null || connections.get(connection.getPlayerUUID()) != connection) return;
            ServerPlayer player = server.getPlayerList().getPlayer(connection.getPlayerUUID());
            if (player == null || spectatorBlocked(player)) return;
            NetManager.sendToClient(player, new ProxyAudioPacket(ProxyMessages.encode(ProxyMessages.SOUND,
                    proxyRoutingGeneration, connection.getProxySession(), message.writePlaintext())));
        } else {
            socket.send(message.writeServer(this, connection), connection.getAddress());
        }
    }

    private List<UUID> getProxyTargets(ServerPlayer sender, boolean whispering) {
        PlayerState senderState = playerStateManager.getState(sender.getUUID());
        if (senderState == null) {
            return List.of();
        }

        @Nullable Group senderGroup = senderState.hasGroup() ? groupManager.getGroup(senderState.getGroup()) : null;

        float distance = whispering
                ? Voicechat.SERVER_CONFIG.whisperDistance.get().floatValue()
                : (float) getBroadcastRange(Utils.getDefaultDistanceServer());
        double maxDistanceSquared = distance * distance;
        List<UUID> targets = new ArrayList<>();

        for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
            if (receiver == sender || spectatorBlocked(receiver)) {
                continue;
            }
            PlayerState receiverState = playerStateManager.getState(receiver.getUUID());
            if (receiverState == null || receiverState.isDisconnected() || receiverState.isDisabled()) {
                continue;
            }
            if (!PermissionManager.INSTANCE.LISTEN_PERMISSION.hasPermission(receiver)) {
                continue;
            }
            if (!CommonCompatibilityManager.INSTANCE.canSee(receiver, sender)) {
                continue;
            }

            boolean sameGroup = senderState.hasGroup() && senderState.getGroup().equals(receiverState.getGroup());
            if (sameGroup) {
                continue;
            }
            if (!sameGroup && receiver.level() != sender.level()) {
                continue;
            }
            @Nullable Group receiverGroup = receiverState.hasGroup() ? groupManager.getGroup(receiverState.getGroup()) : null;
            if (receiverGroup != null && receiverGroup.isIsolated() && !sameGroup) {
                continue;
            }
            if (!sameGroup && senderGroup != null && !senderGroup.isOpen()) {
                continue;
            }
            boolean inRange = receiver.distanceToSqr(sender) <= maxDistanceSquared;
            if (sameGroup || inRange) {
                targets.add(receiver.getUUID());
            }
        }
        return List.copyOf(targets);
    }

    private List<UUID> getProxyGroupTargets(ServerPlayer sender) {
        PlayerState senderState = playerStateManager.getState(sender.getUUID());
        if (senderState == null || !senderState.hasGroup()) return List.of();
        List<UUID> targets = new ArrayList<>();
        for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
            if (receiver == sender || spectatorBlocked(receiver)) continue;
            PlayerState receiverState = playerStateManager.getState(receiver.getUUID());
            if (receiverState == null || receiverState.isDisconnected() || receiverState.isDisabled()
                    || !senderState.getGroup().equals(receiverState.getGroup())) continue;
            if (!CommonCompatibilityManager.INSTANCE.canSee(receiver, sender)
                    || !PermissionManager.INSTANCE.LISTEN_PERMISSION.hasPermission(receiver)) continue;
            targets.add(receiver.getUUID());
        }
        return List.copyOf(targets);
    }

    public void onMicPacket(UUID playerUuid, MicPacket packet) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
        if (player == null) {
            return;
        }
        if (voiceAvailability(player) != VoiceAvailability.AVAILABLE) return;
        if (!PermissionManager.INSTANCE.SPEAK_PERMISSION.hasPermission(player)) {
            CooldownTimer.run("no-speak-" + playerUuid, 30_000L, () -> {
                player.sendOverlayMessage(Component.translatable("message.voicechat.no_speak_permission"));
            });
            return;
        }
        PlayerState state = playerStateManager.getState(player.getUUID());
        if (state == null) {
            return;
        }
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
        if (spectatorBlocked(receiver)) return;
        PluginManager.instance().onListenerAudio(receiver.getUUID(), soundPacket);

        if (connection == null) {
            return;
        }

        if (receiverState.isDisabled() || receiverState.isDisconnected()) {
            return;
        }

        if (PluginManager.instance().onSoundPacket(sender, senderState, receiver, receiverState, soundPacket, source)) {
            return;
        }

        if (!PermissionManager.INSTANCE.LISTEN_PERMISSION.hasPermission(receiver)) {
            CooldownTimer.run(String.format("no-listen-%s", receiver.getUUID()), 30_000L, () -> {
                receiver.sendOverlayMessage(Component.translatable("message.voicechat.no_listen_permission"));
            });
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
        long timestamp = System.currentTimeMillis();

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
                closeConnection(connection);
                secrets.remove(connection.getPlayerUUID());
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

    @Nullable
    public ClientConnection getSender(NetworkMessage message) {
        return connections
                .values()
                .stream()
                .filter(connection -> connection.getAddress().equals(message.getAddress()))
                .findAny()
                .orElse(null);
    }

    @Nullable
    public ClientConnection getUnconnectedSender(NetworkMessage message) {
        return unCheckedConnections
                .values()
                .stream()
                .filter(connection -> connection.getAddress().equals(message.getAddress()))
                .findAny()
                .orElse(null);
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
        connection.send(this, new NetworkMessage(packet));
    }

    public void addUncheckedPlayerConnection(UUID uuid, ClientConnection connection) {
        unCheckedConnections.put(uuid, connection);
    }

    public void addRawPacket(RawUdpPacket rawUdpPacket) {
        packetQueue.add(rawUdpPacket);
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

    public MinecraftServer getServer() {
        return server;
    }
}
