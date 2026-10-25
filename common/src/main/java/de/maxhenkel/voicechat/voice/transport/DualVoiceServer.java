package de.maxhenkel.voicechat.voice.transport;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/** Atomically binds enabled transports; bounded, separate queues prevent UDP starving TCP. */
public final class DualVoiceServer implements AutoCloseable {
    public static final int MAX_DATAGRAM_SIZE = 4096;
    private final TransportMode mode;
    private final int timeout;
    private final BlockingQueue<TcpServer.ReceivedPacket> tcpPackets = new ArrayBlockingQueue<>(2048);
    private final BlockingQueue<TcpServer.ReceivedPacket> udpPackets = new ArrayBlockingQueue<>(1024);
    private final Object signal = new Object();
    private volatile TcpServer tcp;
    private volatile DatagramSocket udp;
    private volatile boolean closed = true;
    private volatile IOException failure;
    private boolean preferUdp;
    private int port = -1;

    public DualVoiceServer(TransportMode mode, int timeout) { this.mode = mode; this.timeout = timeout; }

    public synchronized void open(int port, String bindAddress) throws IOException {
        if (!closed || tcp != null || udp != null) throw new IllegalStateException("Voice listener already opened");
        TcpServer nextTcp = null;
        DatagramSocket nextUdp = null;
        try {
            InetAddress bind = bindAddress == null || bindAddress.isBlank() ? null : InetAddress.getByName(bindAddress);
            if (mode.tcp()) {
                nextTcp = new TcpServer(timeout, true);
                nextTcp.open(port, bindAddress);
                port = nextTcp.getLocalPort();
            }
            if (mode.udp()) {
                nextUdp = new DatagramSocket(null);
                nextUdp.bind(new InetSocketAddress(bind, port));
                port = nextUdp.getLocalPort();
            }
        } catch (IOException | RuntimeException e) {
            if (nextTcp != null) nextTcp.close();
            if (nextUdp != null) nextUdp.close();
            throw e;
        }
        this.port = port;
        tcp = nextTcp;
        udp = nextUdp;
        closed = false;
        if (tcp != null) Thread.ofVirtual().name("voicechat-tcp-inbound").start(this::readTcp);
        if (udp != null) Thread.ofVirtual().name("voicechat-udp-inbound").start(this::readUdp);
    }

    private void readTcp() {
        try {
            while (!closed) {
                TcpServer.ReceivedPacket packet = tcp.read();
                if (!tcpPackets.offer(packet)) tcp.closeConnection(packet.address());
                synchronized (signal) { signal.notifyAll(); }
            }
        } catch (IOException | InterruptedException e) { fail(e); }
    }

    private void readUdp() {
        byte[] buffer = new byte[MAX_DATAGRAM_SIZE + 1];
        try {
            while (!closed) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udp.receive(packet);
                if (packet.getLength() == 0 || packet.getLength() > MAX_DATAGRAM_SIZE) continue;
                // Drop excess datagrams without allocating unbounded per-source state.
                if (udpPackets.remainingCapacity() == 0) continue;
                udpPackets.offer(new TcpServer.ReceivedPacket(Arrays.copyOf(buffer, packet.getLength()),
                        VoiceEndpoint.udp(packet.getSocketAddress()), System.currentTimeMillis()));
                synchronized (signal) { signal.notifyAll(); }
            }
        } catch (IOException e) { fail(e); }
    }

    private void fail(Exception error) {
        if (!closed) { failure = new IOException("Voice listener failed", error); close(); }
    }

    public TcpServer.ReceivedPacket read() throws IOException, InterruptedException {
        synchronized (signal) {
            while (!closed) {
                TcpServer.ReceivedPacket packet = (preferUdp ? udpPackets : tcpPackets).poll();
                if (packet == null) packet = (preferUdp ? tcpPackets : udpPackets).poll();
                preferUdp = !preferUdp;
                if (packet != null) {
                    if (VoiceProtocol.TCP.matches(packet.address()) && !tcp.isConnected(packet.address())) continue;
                    return packet;
                }
                signal.wait();
            }
        }
        throw failure == null ? new IOException("Voice listener closed") : failure;
    }

    public void send(byte[] data, SocketAddress address) throws IOException {
        if (closed) return;
        if (address instanceof VoiceEndpoint endpoint && endpoint.protocol() == VoiceProtocol.LEGACY_UDP) {
            if (udp == null || data.length > MAX_DATAGRAM_SIZE) throw new IOException("Invalid UDP send");
            udp.send(new DatagramPacket(data, data.length, endpoint.remote()));
        } else if (tcp != null) { tcp.send(data, address); }
    }
    public boolean isConnected(SocketAddress address) {
        return !closed && (VoiceProtocol.LEGACY_UDP.matches(address) ? udp != null : tcp != null && tcp.isConnected(address));
    }
    public void closeConnection(SocketAddress address) {
        if (tcp != null && VoiceProtocol.TCP.matches(address)) tcp.closeConnection(address);
    }
    public int getLocalPort() { return port; }
    public boolean isClosed() { return closed; }
    @Override public synchronized void close() {
        closed = true;
        if (tcp != null) tcp.close();
        if (udp != null) udp.close();
        synchronized (signal) { tcpPackets.clear(); udpPackets.clear(); signal.notifyAll(); }
    }
}
