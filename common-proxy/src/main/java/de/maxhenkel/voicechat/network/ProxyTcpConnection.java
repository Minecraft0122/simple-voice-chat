package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.voice.transport.TcpConnection;
import de.maxhenkel.voicechat.voice.transport.VoiceHandshake;
import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

/** Session state around the same bounded, deadline-enforced transport used by direct servers. */
final class ProxyTcpConnection implements AutoCloseable {
    final long createdAt = System.nanoTime();
    final UUID sessionId = UUID.randomUUID();
    UUID backendPlayer;
    UUID proxyPlayer;
    VoiceHandshake handshake;
    volatile boolean authenticated;
    volatile boolean validated;
    volatile long generation;
    volatile int availability = -1;
    private final TcpConnection transport;

    ProxyTcpConnection(Socket socket) throws IOException { transport = new TcpConnection(socket); }
    byte[] read() throws IOException { return transport.read(); }
    void send(byte[] frame) throws IOException { transport.send(frame); }
    boolean isClosed() { return transport.isClosed(); }
    @Override public void close() { transport.close(); }
}
