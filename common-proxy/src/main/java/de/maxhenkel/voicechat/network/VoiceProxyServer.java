package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.VoiceProxy;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Central TCP voice endpoint. Audio is decrypted, routed and re-encrypted here. */
public class VoiceProxyServer extends Thread {

    private static final UUID PING_UUID = UUID.fromString("58bc9ae9-c7a8-45e4-a11c-efbb67199425");

    private final VoiceProxy voiceProxy;
    private final Map<UUID, ProxyTcpConnection> playerConnections = new ConcurrentHashMap<>();
    private final Map<ProxyTcpConnection, UUID> connectionPlayers = new ConcurrentHashMap<>();
    private final Map<ProxyTcpConnection, Long> lastSequences = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;

    public VoiceProxyServer(VoiceProxy proxy) {
        setDaemon(true);
        setName("VoiceProxyServer");
        voiceProxy = proxy;
    }

    @Override
    public void interrupt() {
        super.interrupt();
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
        playerConnections.values().forEach(ProxyTcpConnection::close);
        playerConnections.clear();
        connectionPlayers.clear();
        lastSequences.clear();
    }

    @Override
    public void run() {
        try {
            serverSocket = openSocket();
            while (!isInterrupted() && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    ProxyTcpConnection connection = new ProxyTcpConnection(socket);
                    Thread.ofVirtual().name("voicechat-proxy-tcp-reader").start(() -> readLoop(connection));
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        voiceProxy.getLogger().debug("An exception occurred while accepting a voice TCP connection", e);
                    }
                }
            }
        } catch (Throwable e) {
            voiceProxy.getLogger().error("The voice chat proxy server encountered a fatal error and has been shut down", e);
        } finally {
            interrupt();
        }
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
            while (!isInterrupted() && !connection.isClosed()) {
                handlePacket(connection.read(), connection);
            }
        } catch (Exception e) {
            if (!connection.isClosed()) {
                voiceProxy.getLogger().debug("Voice proxy client connection closed", e);
            }
        } finally {
            UUID playerUUID = connectionPlayers.remove(connection);
            lastSequences.remove(connection);
            if (playerUUID != null) playerConnections.remove(playerUUID, connection);
            connection.close();
        }
    }

    private void handlePacket(byte[] raw, ProxyTcpConnection connection) throws Exception {
        UUID backendUUID = readPlayerUUID(raw);
        if (isPing(raw, backendUUID)) {
            if (voiceProxy.getConfig().allowPings.get()) {
                connection.send(buildPingResponse(raw));
            }
            return;
        }
        UUID playerUUID = voiceProxy.getSniffer().getMappedPlayerUUID(backendUUID);
        byte[] secret = voiceProxy.getSniffer().getSecret(playerUUID);
        if (secret == null) return;

        ProxyVoicePacketCodec.DecodedPacket packet = ProxyVoicePacketCodec.decode(raw, secret, backendUUID);
        if (packet.type() == 0x05) {
            if (!backendUUID.equals(packet.playerUUID()) || packet.authenticatedSecret() == null
                    || !MessageDigest.isEqual(secret, packet.authenticatedSecret())) {
                return;
            }
            ProxyTcpConnection old = playerConnections.put(playerUUID, connection);
            if (old != null && old != connection) old.close();
            connectionPlayers.put(connection, playerUUID);
            lastSequences.remove(connection);
            connection.send(ProxyVoicePacketCodec.encodeControl(secret, (byte) 0x06));
            return;
        }

        if (!playerUUID.equals(connectionPlayers.get(connection))) return;
        if (packet.type() == 0x09) {
            connection.send(ProxyVoicePacketCodec.encodeControl(secret, (byte) 0x0A));
            return;
        }
        if (packet.type() != 0x01 || packet.audio() == null) return;

        Long previous = lastSequences.putIfAbsent(connection, packet.sequence());
        if (previous != null && packet.sequence() <= previous) return;
        if (previous != null) lastSequences.put(connection, packet.sequence());

        VoiceProxySniffer.RoutingState state = voiceProxy.getSniffer().getRoutingState(playerUUID);
        if (state == null || !state.connected()) return;
        for (UUID target : packet.whispering() ? state.whisperTargets() : state.normalTargets()) {
            if (target.equals(playerUUID)) continue;
            ProxyTcpConnection targetConnection = playerConnections.get(target);
            byte[] targetSecret = voiceProxy.getSniffer().getSecret(target);
            if (targetConnection == null || targetSecret == null || targetConnection.isClosed()) continue;
            try {
                targetConnection.send(ProxyVoicePacketCodec.encodePlayerSound(
                        targetSecret, playerUUID, playerUUID, packet.audio(), packet.sequence(),
                        packet.whispering(), packet.whispering() ? state.whisperDistance() : state.normalDistance()
                ));
            } catch (IOException e) {
                closePlayer(target);
            }
        }
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

    public void disconnect(UUID playerUUID) {
        ProxyTcpConnection connection = playerConnections.remove(playerUUID);
        if (connection != null) {
            connectionPlayers.remove(connection);
            lastSequences.remove(connection);
            connection.close();
        }
    }

    /**
     * Kept for the upstream ping helper and source compatibility with proxy add-ons.
     */
    public VoiceProxy getVoiceProxy() {
        return voiceProxy;
    }

    /** Kept for source compatibility with the upstream bridge manager. */
    @Deprecated
    public void write(java.net.DatagramPacket ignored) {
    }
}
