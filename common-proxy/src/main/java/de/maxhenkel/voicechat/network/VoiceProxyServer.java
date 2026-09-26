package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.VoiceProxy;
import de.maxhenkel.voicechat.sniffer.VoiceProxySniffer;
import de.maxhenkel.voicechat.voice.transport.*;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Central audio endpoint; the backend control channel carries audio only for packet-dependent addons. */
public class VoiceProxyServer extends Thread {
    private static final UUID PING_UUID = UUID.fromString("58bc9ae9-c7a8-45e4-a11c-efbb67199425");
    private final VoiceProxy voiceProxy;
    private final Set<ProxyTcpConnection> allConnections = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ProxyTcpConnection> playerConnections = new ConcurrentHashMap<>();
    private final Map<ProxyTcpConnection, Long> lastSequences = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;
    private volatile Thread keepAliveThread;
    public VoiceProxyServer(VoiceProxy proxy) { voiceProxy = proxy; setName("VoiceProxyServer"); setDaemon(true); }

    @Override public void interrupt() {
        super.interrupt();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        allConnections.forEach(ProxyTcpConnection::close);
        allConnections.clear();
        playerConnections.clear();
        lastSequences.clear();
        if (keepAliveThread != null) keepAliveThread.interrupt();
    }

    @Override public void run() {
        try {
            serverSocket = openSocket();
            keepAliveThread = Thread.ofVirtual().name("voicechat-proxy-keepalive").start(this::keepAliveLoop);
            while (!isInterrupted()) {
                Socket socket = serverSocket.accept();
                if (allConnections.size() >= 4096) { socket.close(); continue; }
                ProxyTcpConnection connection = new ProxyTcpConnection(socket);
                allConnections.add(connection);
                Thread.ofVirtual().name("voicechat-proxy-reader").start(() -> readLoop(connection));
            }
        } catch (IOException e) {
            if (!isInterrupted()) voiceProxy.getLogger().error("Voice TCP proxy stopped", e);
        } finally { interrupt(); }
    }

    private ServerSocket openSocket() throws IOException {
        int port = voiceProxy.getPort();
        String bindAddress = voiceProxy.getConfig().bindAddress.get();
        InetAddress address = null;
        if (bindAddress == null || bindAddress.isEmpty()) {
            address = voiceProxy.getDefaultBindSocket().getAddress();
        } else if (!bindAddress.trim().equals("*")) {
            try {
                address = InetAddress.getByName(bindAddress);
            } catch (Exception e) {
                voiceProxy.getLogger().error("Invalid voice proxy bind address '{}', using proxy bind address", bindAddress);
                address = voiceProxy.getDefaultBindSocket().getAddress();
            }
        }

        ServerSocket listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(address, port));
            voiceProxy.getLogger().info("Voice chat TCP proxy started at {}:{}", bindAddress, listener.getLocalPort());
            return listener;
        } catch (BindException e) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            if (address == null || "0.0.0.0".equals(bindAddress)) throw e;
            voiceProxy.getLogger().error("Failed to bind voice proxy to '{}', binding to wildcard address instead", bindAddress);
            ServerSocket fallback = new ServerSocket();
            fallback.bind(new InetSocketAddress((InetAddress) null, port));
            return fallback;
        }
    }


    private void readLoop(ProxyTcpConnection connection) {
        try {
            while (!isInterrupted() && !connection.isClosed()) handlePacket(connection.read(), connection);
        } catch (Exception e) {
            if (!connection.isClosed()) voiceProxy.getLogger().debug("Voice connection closed", e);
        } finally {
            if (connection.proxyPlayer != null && playerConnections.remove(connection.proxyPlayer, connection)) {
                notifyBackend(connection, false);
            }
            allConnections.remove(connection);
            lastSequences.remove(connection);
            connection.close();
        }
    }

    private void keepAliveLoop() {
        try {
            while (!isInterrupted()) {
                Thread.sleep(1000L);
                voiceProxy.getSniffer().checkBackendTimeouts();
                for (ProxyTcpConnection connection : allConnections) {
                    if (!connection.validated && System.nanoTime() - connection.createdAt > 10_000_000_000L) { connection.close(); continue; }
                    if (!connection.authenticated || connection.isClosed()) continue;
                    try {
                        refreshAvailability(connection);
                        connection.send(ProxyVoicePacketCodec.encodeControl(connection.handshake.serverKey(), (byte) 0x08));
                        if (connection.validated && voiceProxy.getSniffer().getRoutingState(connection.proxyPlayer) != null) notifyBackend(connection, true);
                    } catch (Exception e) { connection.close(); }
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void handlePacket(byte[] raw, ProxyTcpConnection connection) throws Exception {
        UUID backendUUID = readPlayerUUID(raw);
        if (isPing(raw, backendUUID)) {
            if (voiceProxy.getConfig().allowPings.get()) connection.send(buildPingResponse(raw));
            return;
        }
        UUID player = voiceProxy.getSniffer().getMappedPlayerUUID(backendUUID);
        if (player == null) {
            voiceProxy.getLogger().warn("Rejected voice connection: backend did not upload UUID mapping for {}", backendUUID);
            connection.close(); return;
        }
        if (connection.proxyPlayer != null && (!connection.proxyPlayer.equals(player) || !connection.backendPlayer.equals(backendUUID))) {
            connection.close(); return;
        }
        byte[] secret = voiceProxy.getSniffer().getSecret(player);
        if (secret == null) { connection.close(); return; }
        ProxyVoicePacketCodec.DecodedPacket packet;
        if (connection.authenticated) {
            try { packet = ProxyVoicePacketCodec.decode(raw, connection.handshake.clientKey(), backendUUID); }
            catch (Exception e) {
                packet = ProxyVoicePacketCodec.decode(raw, secret, backendUUID);
                // Repeated handshake messages cannot reset a session or its replay counter.
                if (packet.type() != 0x05 && packet.type() != 0x0C) throw e;
                connection.send(ProxyVoicePacketCodec.encodeControl(connection.handshake.serverKey(), (byte) 0x06));
                return;
            }
        } else {
            packet = ProxyVoicePacketCodec.decode(raw, secret, backendUUID);
        }
        if (!connection.authenticated && packet.type() == 0x05) {
            if (!backendUUID.equals(packet.playerUUID()) || !MessageDigest.isEqual(secret, packet.authenticatedSecret())) return;
            connection.proxyPlayer = player;
            connection.backendPlayer = backendUUID;
            if (connection.handshake == null || connection.handshake.expired()) connection.handshake = VoiceHandshake.create(secret, backendUUID);
            connection.send(ProxyVoicePacketCodec.encodeChallenge(secret, connection.handshake.challenge()));
            return;
        }
        if (!connection.authenticated && packet.type() == 0x0C) {
            if (connection.handshake == null || !connection.handshake.verify(packet.authenticatedSecret())) { connection.close(); return; }
            VoiceProxySniffer.RoutingState state = voiceProxy.getSniffer().getRoutingState(player);
            if (state == null) return; // Wait for authoritative backend metadata before accepting the session.
            connection.generation = state.generation();
            connection.authenticated = true;
            ProxyTcpConnection old = playerConnections.put(player, connection);
            if (old != null && old != connection) old.close();
            connection.send(ProxyVoicePacketCodec.encodeControl(connection.handshake.serverKey(), (byte) 0x06));
            return;
        }
        if (!connection.authenticated || playerConnections.get(player) != connection) return;
        if (packet.type() == 0x09) {
            connection.validated = true;
            connection.send(ProxyVoicePacketCodec.encodeControl(connection.handshake.serverKey(), (byte) 0x0A));
            notifyBackend(connection, true);
            refreshAvailability(connection);
            return;
        }
        if (packet.type() == 0x08) return;
        if (connection.validated && packet.type() == 0x07) {
            voiceProxy.sendToBackend(player, VoiceProxy.PROXY_CONTROL_CHANNEL, ProxyMessages.encode(ProxyMessages.PONG,
                    connection.generation, connection.sessionId, packet.pong()));
            return;
        }
        if (!connection.validated || packet.type() != 0x01 || packet.audio() == null) return;
        Long previous = lastSequences.get(connection);
        if (packet.sequence() < 0 || (previous != null && packet.sequence() <= previous)) return;
        lastSequences.put(connection, packet.sequence());
        VoiceProxySniffer.RoutingState state = voiceProxy.getSniffer().getRoutingState(player);
        if (state == null || !state.connected()) { refreshAvailability(connection); return; }
        if (state.relay()) {
            voiceProxy.sendToBackend(player, VoiceProxy.PROXY_CONTROL_CHANNEL, ProxyMessages.encode(ProxyMessages.MICROPHONE,
                    connection.generation, connection.sessionId, ProxyVoicePacketCodec.microphonePayload(packet)));
            return;
        }
        for (UUID target : state.groupTargets()) sendAudio(connection, target, packet, true, state);
        for (UUID target : packet.whispering() ? state.whisperTargets() : state.normalTargets()) sendAudio(connection, target, packet, false, state);
    }

    private void sendAudio(ProxyTcpConnection sender, UUID backendTarget, ProxyVoicePacketCodec.DecodedPacket packet,
                           boolean group, VoiceProxySniffer.RoutingState state) throws Exception {
        UUID target = voiceProxy.getSniffer().getMappedPlayerUUID(backendTarget);
        if (target == null) {
            if (voiceProxy.getSniffer().recentlyDisconnected(backendTarget)) return;
            voiceProxy.getSniffer().internalError(sender.proxyPlayer, "Backend routed voice to a player without an uploaded UUID: " + backendTarget);
            return;
        }
        if (target.equals(sender.proxyPlayer) || !voiceProxy.getSniffer().sameBackend(sender.proxyPlayer, target)) return;
        ProxyTcpConnection receiver = playerConnections.get(target);
        if (!canReceive(receiver)) return;
        try {
            byte[] bytes = group
                    ? ProxyVoicePacketCodec.encodeGroupSound(receiver.handshake.serverKey(), sender.backendPlayer, sender.backendPlayer, packet.audio(), packet.sequence())
                    : ProxyVoicePacketCodec.encodePlayerSound(receiver.handshake.serverKey(), sender.backendPlayer, sender.backendPlayer, packet.audio(), packet.sequence(),
                        packet.whispering(), packet.whispering() ? state.whisperDistance() : state.normalDistance());
            receiver.send(bytes);
        } catch (IOException e) { receiver.close(); }
    }

    private boolean canReceive(ProxyTcpConnection connection) {
        if (connection == null || !connection.validated || connection.isClosed()) return false;
        VoiceProxySniffer.RoutingState state = voiceProxy.getSniffer().getRoutingState(connection.proxyPlayer);
        return state != null && (state.status() == VoiceAvailability.AVAILABLE || state.status() == VoiceAvailability.NO_PERMISSION);
    }

    private void refreshAvailability(ProxyTcpConnection connection) throws Exception {
        if (!connection.validated) return;
        VoiceProxySniffer.RoutingState state = voiceProxy.getSniffer().getRoutingState(connection.proxyPlayer);
        int status = state == null ? VoiceAvailability.WAITING : state.status();
        if (connection.availability == status) return;
        connection.availability = status;
        connection.send(ProxyVoicePacketCodec.encodePayload(connection.handshake.serverKey(), new byte[]{0x0D, (byte) status}));
    }

    private void notifyBackend(ProxyTcpConnection connection, boolean connected) {
        if (!connection.validated) return;
        voiceProxy.sendToBackend(connection.proxyPlayer, VoiceProxy.PROXY_CONTROL_CHANNEL,
                ProxyMessages.encode(ProxyMessages.CONNECTION, connection.generation, connection.sessionId, new byte[]{(byte) (connected ? 1 : 0)}));
    }

    public void routingChanged(UUID player) {
        ProxyTcpConnection connection = playerConnections.get(player);
        if (connection == null) return;
        try { refreshAvailability(connection); } catch (Exception e) { connection.close(); }
    }

    public void relayBackendAudio(UUID player, byte[] bytes) {
        try {
            ProxyMessages.Message message = ProxyMessages.decode(bytes);
            ProxyTcpConnection connection = playerConnections.get(player);
            if (!canReceive(connection) || message.type() != ProxyMessages.SOUND || !message.session().equals(connection.sessionId)
                    || message.generation() != connection.generation || message.payload().length == 0) return;
            int type = message.payload()[0] & 255;
            if (type != 0x02 && type != 0x03 && type != 0x04 && type != 0x07) throw new IllegalArgumentException("Invalid backend audio packet type");
            connection.send(ProxyVoicePacketCodec.encodePayload(connection.handshake.serverKey(), message.payload()));
        } catch (Exception e) { voiceProxy.getLogger().warn("Rejected backend audio for {}", player); }
    }

    private static UUID readPlayerUUID(byte[] raw) {
        if (raw == null || raw.length < 17 || raw[0] != (byte) 0xFF) {
            throw new IllegalArgumentException("Invalid voice proxy packet header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, 1, 16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static boolean isPing(byte[] raw, UUID packetUUID) {
        return raw.length >= 18 && packetUUID.equals(PING_UUID);
    }

    private static byte[] buildPingResponse(byte[] raw) {
        int index = 17;
        int length = 0;
        int shift = 0;
        while (index < raw.length && shift < 35) {
            int value = raw[index++] & 0xFF;
            length |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) break;
            shift += 7;
        }
        if (length != 24 || index + length > raw.length) throw new IllegalArgumentException("Invalid voice ping payload");
        return java.util.Arrays.copyOfRange(raw, index, index + length);
    }


    public void disconnect(UUID player) {
        ProxyTcpConnection connection = playerConnections.remove(player);
        if (connection != null) {
            notifyBackend(connection, false);
            lastSequences.remove(connection);
            connection.close();
        }
    }
    public VoiceProxy getVoiceProxy() { return voiceProxy; }
    @Deprecated public void write(java.net.DatagramPacket ignored) {}
}
