package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.voice.transport.*;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.UUID;

/** One protocol-bound voice session, independent of the listener transport. */
final class ProxyVoiceSession implements AutoCloseable {
    final long createdAt = System.nanoTime();
    final UUID sessionId = UUID.randomUUID();
    final SocketAddress address;
    final VoiceProtocol protocol;
    UUID backendPlayer;
    UUID proxyPlayer;
    VoiceHandshake handshake;
    byte[] masterSecret;
    volatile boolean authenticated;
    volatile boolean validated;
    volatile long generation;
    volatile int availability = -1;
    volatile long lastReceived = System.nanoTime();
    private volatile boolean closed;
    private final DualVoiceServer transport;

    ProxyVoiceSession(DualVoiceServer transport, SocketAddress address) {
        this.transport = transport;
        this.address = address;
        protocol = VoiceProtocol.LEGACY_UDP.matches(address) ? VoiceProtocol.LEGACY_UDP : VoiceProtocol.TCP;
    }
    boolean isLegacy() { return protocol == VoiceProtocol.LEGACY_UDP; }
    byte[] readKey() { return isLegacy() ? masterSecret : handshake.clientKey(); }
    byte[] writeKey() { return isLegacy() ? masterSecret : handshake.serverKey(); }
    void send(byte[] frame) throws IOException { if (!isClosed()) transport.send(frame, address); }
    boolean isClosed() { return closed || !transport.isConnected(address); }
    @Override public void close() { closed = true; transport.closeConnection(address); }
}
