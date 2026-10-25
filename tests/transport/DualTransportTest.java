import de.maxhenkel.voicechat.voice.transport.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Real socket checks for atomic bind, isolated endpoints, framing and shutdown. */
public class DualTransportTest {
    private static int passed;
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    static byte[] receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[4097], 4097);
        socket.receive(packet);
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }
    static void send(DatagramSocket socket, int port, byte[] data) throws Exception {
        socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
    }
    static TcpServer.ReceivedPacket read(DualVoiceServer server) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TcpServer.ReceivedPacket> future = executor.submit(server::read);
            try { return future.get(2, TimeUnit.SECONDS); }
            finally { future.cancel(true); }
        }
    }
    public static void main(String[] args) throws Exception {
        check(TransportMode.parse("dual").supports(VoiceProtocol.LEGACY_UDP), "dual accepts upstream protocol 20");
        check(!TransportMode.TCP.supports(VoiceProtocol.LEGACY_UDP) && !TransportMode.UDP.supports(VoiceProtocol.TCP), "single transport modes reject the other profile");
        check(VoiceProtocol.fromVersion(19) == null && VoiceProtocol.fromVersion(21) == null && VoiceProtocol.fromVersion(1022) == null, "unknown versions are not accepted");
        try { TransportMode.parse("auto"); throw new AssertionError("invalid mode accepted"); } catch (IllegalArgumentException expected) { passed++; }
        try (DualVoiceServer server = new DualVoiceServer(TransportMode.DUAL, 30000)) {
            server.open(0, "127.0.0.1");
            try (Socket tcp = new Socket("127.0.0.1", server.getLocalPort());
                 DatagramSocket udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", tcp.getLocalPort()))) {
                udp.setSoTimeout(1500); tcp.setSoTimeout(1500);
                DataOutputStream out = new DataOutputStream(tcp.getOutputStream());
                DataInputStream in = new DataInputStream(tcp.getInputStream());
                byte[] a = {1, 2, 3}, b = {4, 5, 6};
                out.writeInt(a.length); out.write(a); out.flush();
                TcpServer.ReceivedPacket first = read(server);
                send(udp, server.getLocalPort(), b);
                TcpServer.ReceivedPacket second = read(server);
                check(Arrays.equals(first.data(), a) && Arrays.equals(second.data(), b), "TCP framing and raw UDP payload coexist on one port");
                check(!first.address().equals(second.address()), "same source IP and port have distinct TCP/UDP identities");
                server.send(a, first.address()); server.send(b, second.address());
                check(in.readInt() == a.length && Arrays.equals(in.readNBytes(a.length), a), "TCP response goes to the TCP stream");
                check(Arrays.equals(receive(udp), b), "UDP response has no TCP length prefix");
                send(udp, server.getLocalPort(), new byte[4097]);
                send(udp, server.getLocalPort(), a);
                check(Arrays.equals(read(server).data(), a), "oversized datagrams are discarded without truncation");
                server.closeConnection(first.address());
                check(in.read() == -1, "closing TCP releases that stream");
                send(udp, server.getLocalPort(), b);
                check(Arrays.equals(read(server).data(), b), "closing TCP does not disrupt UDP at the same address");
                try (Socket next = new Socket("127.0.0.1", server.getLocalPort())) {
                    DataOutputStream nextOut = new DataOutputStream(next.getOutputStream());
                    nextOut.writeInt(1); nextOut.write(7); nextOut.flush();
                    check(!read(server).address().equals(first.address()), "a new TCP connection has a fresh endpoint identity");
                }
            }
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> waiting = executor.submit(() -> { try { server.read(); throw new AssertionError("read after close"); } catch (IOException expected) {} catch (InterruptedException e) { throw new RuntimeException(e); } });
                server.close(); waiting.get(2, TimeUnit.SECONDS);
                check(server.isClosed(), "shutdown unblocks a waiting reader");
            }
        }
        try (DatagramSocket occupied = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DualVoiceServer server = new DualVoiceServer(TransportMode.DUAL, 30000)) {
            int port = occupied.getLocalPort();
            try { server.open(port, "127.0.0.1"); throw new AssertionError("UDP bind collision ignored"); } catch (BindException expected) {}
            try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                check(server.isClosed(), "UDP bind failure rolls back the TCP listener");
            }
            occupied.close();
            server.open(port, "127.0.0.1");
            check(!server.isClosed(), "an atomic bind failure can be retried");
        }
        for (TransportMode mode : List.of(TransportMode.TCP, TransportMode.UDP)) {
            try (DualVoiceServer server = new DualVoiceServer(mode, 30000)) {
                server.open(0, "127.0.0.1");
                if (mode == TransportMode.TCP) {
                    try (DatagramSocket probe = new DatagramSocket(server.getLocalPort())) { check(true, "TCP mode does not bind UDP"); }
                } else {
                    try (ServerSocket probe = new ServerSocket(server.getLocalPort())) { check(true, "UDP mode does not bind TCP"); }
                }
            }
        }
        System.out.println("Dual transport: " + passed + " checks passed");
    }
}
