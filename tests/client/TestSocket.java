package de.maxhenkel.voicechat.voice.client;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.voice.common.*;
import java.net.SocketAddress;
import java.util.concurrent.*;

/** The network boundary is fake; connection failure and retry use the production client classes. */
public class TestSocket implements ClientVoicechatSocket {
    public static final java.util.List<TestSocket> sockets = new CopyOnWriteArrayList<>();
    private final BlockingQueue<RawUdpPacket> incoming = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    public TestSocket() { sockets.add(this); }
    public record Incoming(NetworkMessage message) implements RawUdpPacket {
        public byte[] getData() { return new byte[0]; }
        public long getTimestamp() { return 0; }
        public SocketAddress getSocketAddress() { return null; }
    }
    public void open() {}
    public RawUdpPacket read() throws Exception {
        RawUdpPacket packet = incoming.take();
        if (closed) throw new java.io.EOFException("Test transport failure");
        return packet;
    }
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (closed) throw new java.io.EOFException();
    }
    public void offer(Packet<?> packet) { incoming.add(new Incoming(new NetworkMessage(packet))); }
    public void close() { closed = true; incoming.offer(new Incoming(null)); }
    public boolean isClosed() { return closed; }
    public void ready() {
        offer(new AuthenticateAckPacket());
        offer(new ConnectionCheckAckPacket());
        offer(new AvailabilityPacket(0));
    }
}
