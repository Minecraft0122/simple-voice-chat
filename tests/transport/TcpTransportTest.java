import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.plugins.impl.ClientVoicechatSocketImpl;
import de.maxhenkel.voicechat.voice.transport.TcpConnection;
import de.maxhenkel.voicechat.voice.transport.TcpFrameCodec;
import de.maxhenkel.voicechat.voice.transport.TcpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests the production transport classes directly, with real loopback sockets.
 * No Minecraft runtime, test framework, substitute transport, or generated stubs are used.
 */
public final class TcpTransportTest {

    private static final int TIMEOUT_SECONDS = 15;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final ExecutorService TASKS = Executors.newVirtualThreadPerTaskExecutor();
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        run("codec: minimum and maximum payloads round trip", TcpTransportTest::codecRoundTrip);
        run("codec: four byte network-order length", TcpTransportTest::codecNetworkOrder);
        run("codec: fragmented header and payload", TcpTransportTest::codecFragmentedInput);
        run("codec: coalesced frames stay separate", TcpTransportTest::codecCoalescedInput);
        run("codec: zero, negative, and oversized lengths rejected", TcpTransportTest::codecInvalidLengths);
        run("codec: truncated header rejected", TcpTransportTest::codecTruncatedHeader);
        run("codec: truncated payload rejected", TcpTransportTest::codecTruncatedPayload);
        run("codec: invalid outbound payloads rejected before writing", TcpTransportTest::codecInvalidWrites);
        run("connection: fragmented frames over a real socket", TcpTransportTest::connectionFragmentation);
        run("connection: coalesced frames over a real socket", TcpTransportTest::connectionCoalescing);
        run("connection: concurrent writes preserve frame boundaries", TcpTransportTest::connectionConcurrentWriters);
        run("connection: a stalled recipient has a bounded outbound queue", TcpTransportTest::connectionSlowRecipient);
        run("connection: close unblocks a pending read", TcpTransportTest::connectionCloseUnblocksRead);
        run("connection: remote disconnect is detected", TcpTransportTest::connectionRemoteDisconnect);
        run("server: multiple clients and directed replies", TcpTransportTest::serverRoutesClients);
        run("server: malformed client does not affect another client", TcpTransportTest::serverMalformedClientIsolation);
        run("server: truncated client does not affect another client", TcpTransportTest::serverTruncatedClientIsolation);
        run("server: explicit client disconnect and reconnect", TcpTransportTest::serverDisconnectReconnect);
        run("server: client EOF and replacement connection", TcpTransportTest::serverRemoteDisconnectReconnect);
        run("server: shutdown unblocks read and disconnects clients", TcpTransportTest::serverShutdown);
        run("client adapter: first send connects and read receives a reply", TcpTransportTest::clientAdapterRoundTrip);
        run("client adapter: read waiting for first send unblocks on close", TcpTransportTest::clientAdapterPendingReadClose);
        run("client adapter: open cannot be called twice", TcpTransportTest::clientAdapterRepeatedOpen);
        run("client adapter: non-internet destination is rejected", TcpTransportTest::clientAdapterRejectsWrongAddress);
        run("client adapter: changing the connected destination is rejected", TcpTransportTest::clientAdapterRejectsChangedDestination);
        run("client adapter: refused connection fails promptly", TcpTransportTest::clientAdapterRefusedConnection);
        run("client adapter: remote EOF terminates read", TcpTransportTest::clientAdapterRemoteEof);

        TASKS.shutdownNow();
        System.out.printf("%nTCP transport tests: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            throw new AssertionError(failed + " TCP transport regression test(s) failed");
        }
    }

    private static void codecRoundTrip() throws Exception {
        for (int size : new int[]{1, 2, 255, 256, TcpFrameCodec.MAX_FRAME_SIZE}) {
            byte[] expected = payload(size, 37);
            assertBytes(expected, TcpFrameCodec.read(new ByteArrayInputStream(frame(expected))), "payload size " + size);
        }
    }

    private static void codecNetworkOrder() throws Exception {
        byte[] encoded = frame(payload(258, 19));
        assertBytes(new byte[]{0, 0, 1, 2}, Arrays.copyOf(encoded, 4), "length prefix");
        check(encoded.length == 262, "frame must contain only a four byte header and its payload");
    }

    private static void codecFragmentedInput() throws Exception {
        byte[] expected = payload(1025, 51);
        InputStream fragments = new ByteArrayInputStream(frame(expected)) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        assertBytes(expected, TcpFrameCodec.read(fragments), "one byte at a time");
    }

    private static void codecCoalescedInput() throws Exception {
        byte[][] expected = {bytes("first"), payload(400, 2), bytes("last")};
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] packet : expected) {
            all.write(frame(packet));
        }
        ByteArrayInputStream input = new ByteArrayInputStream(all.toByteArray());
        for (byte[] packet : expected) {
            assertBytes(packet, TcpFrameCodec.read(input), "coalesced payload");
        }
        check(input.available() == 0, "codec must consume each frame exactly once");
    }

    private static void codecInvalidLengths() throws Exception {
        for (int length : new int[]{0, -1, Integer.MIN_VALUE, TcpFrameCodec.MAX_FRAME_SIZE + 1, Integer.MAX_VALUE}) {
            expectIOException(() -> TcpFrameCodec.read(new ByteArrayInputStream(header(length))), "length " + length);
        }
    }

    private static void codecTruncatedHeader() throws Exception {
        for (int size = 1; size < 4; size++) {
            byte[] truncated = Arrays.copyOf(header(20), size);
            expectIOException(() -> TcpFrameCodec.read(new ByteArrayInputStream(truncated)), "header size " + size);
        }
    }

    private static void codecTruncatedPayload() throws Exception {
        byte[] encoded = frame(payload(20, 3));
        for (int payloadSize : new int[]{0, 1, 19}) {
            byte[] truncated = Arrays.copyOf(encoded, 4 + payloadSize);
            expectIOException(() -> TcpFrameCodec.read(new ByteArrayInputStream(truncated)), "payload size " + payloadSize);
        }
    }

    private static void codecInvalidWrites() throws Exception {
        for (byte[] invalid : new byte[][]{new byte[0], new byte[TcpFrameCodec.MAX_FRAME_SIZE + 1]}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                TcpFrameCodec.write(output, invalid);
                throw new AssertionError("invalid outbound length " + invalid.length + " was accepted");
            } catch (IOException | IllegalArgumentException expected) {
                check(output.size() == 0, "invalid write must not put a partial header on the connection");
            }
        }
    }

    private static void connectionFragmentation() throws Exception {
        try (SocketPair pair = SocketPair.create()) {
            TcpConnection receiver = new TcpConnection(pair.accepted());
            byte[] expected = payload(217, 88);
            Future<byte[]> read = TASKS.submit(receiver::read);
            OutputStream output = pair.client().getOutputStream();
            byte[] encoded = frame(expected);
            for (int offset = 0; offset < encoded.length; offset++) {
                output.write(encoded, offset, 1);
                output.flush();
            }
            assertBytes(expected, await(read), "fragmented socket data");
            receiver.close();
        }
    }

    private static void connectionCoalescing() throws Exception {
        try (SocketPair pair = SocketPair.create()) {
            TcpConnection receiver = new TcpConnection(pair.accepted());
            byte[][] expected = {bytes("one"), payload(1024, 4), bytes("three")};
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte[] packet : expected) {
                output.write(frame(packet));
            }
            pair.client().getOutputStream().write(output.toByteArray());
            for (byte[] packet : expected) {
                assertBytes(packet, receiver.read(), "coalesced socket data");
            }
            receiver.close();
        }
    }

    private static void connectionConcurrentWriters() throws Exception {
        final int writers = 6;
        final int framesPerWriter = 32;
        try (SocketPair pair = SocketPair.create()) {
            TcpConnection sender = new TcpConnection(pair.client());
            CountDownLatch start = new CountDownLatch(1);
            // Keep this framing test below the intentional outbound queue limit.
            Semaphore window = new Semaphore(24);
            Future<Set<Integer>> received = TASKS.submit(() -> {
                Set<Integer> ids = new HashSet<>();
                for (int index = 0; index < writers * framesPerWriter; index++) {
                    byte[] packet = TcpFrameCodec.read(pair.accepted().getInputStream());
                    check(packet != null && packet.length >= 4, "received an incomplete frame");
                    int id = ByteBuffer.wrap(packet).getInt();
                    check(ids.add(id), "duplicate frame " + id);
                    assertBytes(writerPayload(id), packet, "concurrent frame " + id);
                    window.release();
                }
                return ids;
            });
            List<Future<Void>> senders = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                int writerId = writer;
                senders.add(TASKS.submit(() -> {
                    start.await();
                    for (int index = 0; index < framesPerWriter; index++) {
                        window.acquire();
                        sender.send(writerPayload(writerId * framesPerWriter + index));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> sending : senders) {
                await(sending);
            }
            check(await(received).size() == writers * framesPerWriter, "not all concurrent frames arrived");
            sender.close();
        }
    }

    private static void connectionSlowRecipient() throws Exception {
        try (SocketPair pair = SocketPair.create()) {
            pair.client().setSendBufferSize(1024);
            pair.accepted().setReceiveBufferSize(1024);
            TcpConnection sender = new TcpConnection(pair.client());
            byte[] packet = payload(TcpFrameCodec.MAX_FRAME_SIZE, 73);
            boolean refused = false;
            // The peer never reads. Even if the OS absorbs a few frames, the
            // bounded queue must eventually reject sends and close this peer.
            for (int index = 0; index < 100_000; index++) {
                try {
                    sender.send(packet);
                } catch (IOException expected) {
                    refused = true;
                    break;
                }
            }
            check(refused, "a non-reading peer accepted an unbounded burst");
            check(sender.isClosed(), "overflow did not close the stalled peer");
            sender.close();
        }
    }

    private static void connectionCloseUnblocksRead() throws Exception {
        try (SocketPair pair = SocketPair.create()) {
            TcpConnection receiver = new TcpConnection(pair.accepted());
            CountDownLatch started = new CountDownLatch(1);
            Future<?> read = TASKS.submit(() -> {
                started.countDown();
                expectReadTermination(receiver::read, "closed connection");
                return null;
            });
            started.await();
            receiver.close();
            await(read);
            check(receiver.isClosed(), "connection did not report closed");
            receiver.close();
            expectIOException(() -> receiver.send(bytes("after close")), "send after close");
        }
    }

    private static void connectionRemoteDisconnect() throws Exception {
        try (SocketPair pair = SocketPair.create()) {
            TcpConnection receiver = new TcpConnection(pair.accepted());
            pair.client().close();
            expectReadTermination(receiver::read, "remote EOF");
            receiver.close();
        }
    }

    private static void serverRoutesClients() throws Exception {
        TcpServer server = openServer();
        try (Socket first = connect(server); Socket second = connect(server)) {
            long before = System.currentTimeMillis();
            TcpFrameCodec.write(first.getOutputStream(), bytes("client A"));
            TcpFrameCodec.write(second.getOutputStream(), bytes("client B"));
            Set<String> packets = new HashSet<>();
            for (int index = 0; index < 2; index++) {
                var packet = server.read();
                String value = new String(packet.data(), StandardCharsets.UTF_8);
                packets.add(value);
                check(packet.timestamp() >= before && packet.timestamp() <= System.currentTimeMillis(), "invalid receive timestamp");
                SocketAddress expectedAddress = value.equals("client A") ? first.getLocalSocketAddress() : second.getLocalSocketAddress();
                check(expectedAddress.equals(packet.address()), "packet attributed to the wrong client");
                server.send(bytes("reply to " + value), packet.address());
            }
            check(packets.equals(Set.of("client A", "client B")), "server lost or duplicated a client packet");
            assertBytes(bytes("reply to client A"), TcpFrameCodec.read(first.getInputStream()), "directed reply A");
            assertBytes(bytes("reply to client B"), TcpFrameCodec.read(second.getInputStream()), "directed reply B");
        } finally {
            server.close();
        }
    }

    private static void serverMalformedClientIsolation() throws Exception {
        TcpServer server = openServer();
        try (Socket healthy = connect(server)) {
            for (int invalidLength : new int[]{0, -1, TcpFrameCodec.MAX_FRAME_SIZE + 1, Integer.MAX_VALUE}) {
                try (Socket malformed = connect(server)) {
                    malformed.getOutputStream().write(header(invalidLength));
                    assertPeerClosed(malformed, "malformed length " + invalidLength);
                }
                verifyEcho(server, healthy, "healthy after " + invalidLength);
            }
        } finally {
            server.close();
        }
    }

    private static void serverTruncatedClientIsolation() throws Exception {
        TcpServer server = openServer();
        try (Socket healthy = connect(server)) {
            for (byte[] truncated : new byte[][]{new byte[]{0, 0}, new byte[]{0, 0, 0, 10, 1, 2}}) {
                try (Socket malformed = connect(server)) {
                    malformed.getOutputStream().write(truncated);
                    malformed.shutdownOutput();
                    assertPeerClosed(malformed, "truncated frame");
                }
                verifyEcho(server, healthy, "healthy after truncated frame");
            }
        } finally {
            server.close();
        }
    }

    private static void serverDisconnectReconnect() throws Exception {
        TcpServer server = openServer();
        try (Socket first = connect(server)) {
            TcpFrameCodec.write(first.getOutputStream(), bytes("initial connection"));
            var packet = server.read();
            server.closeConnection(packet.address());
            assertPeerClosed(first, "explicit closeConnection");
            try (Socket replacement = connect(server)) {
                verifyEcho(server, replacement, "replacement connection");
            }
        } finally {
            server.close();
        }
    }

    private static void serverRemoteDisconnectReconnect() throws Exception {
        TcpServer server = openServer();
        try {
            try (Socket first = connect(server)) {
                verifyEcho(server, first, "before EOF");
            }
            try (Socket replacement = connect(server)) {
                verifyEcho(server, replacement, "after EOF");
            }
        } finally {
            server.close();
        }
    }

    private static void serverShutdown() throws Exception {
        TcpServer server = openServer();
        try (Socket client = connect(server)) {
            verifyEcho(server, client, "before shutdown");
            CountDownLatch started = new CountDownLatch(1);
            Future<?> pendingRead = TASKS.submit(() -> {
                started.countDown();
                expectReadTermination(server::read, "server shutdown");
                return null;
            });
            started.await();
            server.close();
            await(pendingRead);
            check(server.isClosed(), "server did not report closed");
            assertPeerClosed(client, "server shutdown");
            server.close();
        } finally {
            server.close();
        }
    }

    private static void clientAdapterRoundTrip() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 2, LOOPBACK)) {
            ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
            client.open();
            byte[] expected = bytes("adapter round trip");
            Future<byte[]> server = TASKS.submit(() -> {
                try (Socket accepted = listener.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    assertBytes(expected, TcpFrameCodec.read(accepted.getInputStream()), "adapter outbound frame");
                    byte[] reply = bytes("adapter reply");
                    TcpFrameCodec.write(accepted.getOutputStream(), reply);
                    return reply;
                }
            });
            CountDownLatch reading = new CountDownLatch(1);
            Future<RawUdpPacket> pendingRead = TASKS.submit(() -> {
                reading.countDown();
                return client.read();
            });
            reading.await();
            check(!pendingRead.isDone(), "read returned before first send established a connection");
            client.send(expected, new InetSocketAddress(LOOPBACK, listener.getLocalPort()));
            RawUdpPacket packet = await(pendingRead);
            assertBytes(bytes("adapter reply"), packet.getData(), "adapter inbound frame");
            check(packet.getSocketAddress() instanceof InetSocketAddress, "adapter did not expose the peer address");
            check(packet.getTimestamp() > 0, "adapter did not timestamp the inbound frame");
            assertBytes(bytes("adapter reply"), await(server), "server completed reply");
            client.close();
        }
    }

    private static void clientAdapterPendingReadClose() throws Exception {
        ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
        client.open();
        CountDownLatch started = new CountDownLatch(1);
        Future<?> pending = TASKS.submit(() -> {
            started.countDown();
            try {
                client.read();
                throw new AssertionError("read returned before a connection or close");
            } catch (IOException expected) {
                return null;
            }
        });
        check(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "adapter read did not start");
        Thread.sleep(50);
        client.close();
        await(pending);
        check(client.isClosed(), "adapter did not report closed after close");
    }

    private static void clientAdapterRepeatedOpen() throws Exception {
        ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
        client.open();
        try {
            client.open();
            throw new AssertionError("adapter accepted open() twice");
        } catch (IllegalStateException expected) {
            // Expected lifecycle rejection.
        } finally {
            client.close();
        }
        try {
            client.open();
            throw new AssertionError("adapter reopened after close");
        } catch (IllegalStateException expected) {
            // Each connection attempt must use a fresh adapter.
        }
    }

    private static void clientAdapterRejectsWrongAddress() throws Exception {
        ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
        client.open();
        try {
            expectIOException(() -> client.send(bytes("bad address"), new SocketAddress() {
                @Override
                public String toString() {
                    return "unsupported";
                }
            }), "non-internet destination");
        } finally {
            client.close();
        }
    }

    private static void clientAdapterRefusedConnection() throws Exception {
        int unusedPort;
        try (ServerSocket temporary = new ServerSocket(0, 1, LOOPBACK)) {
            unusedPort = temporary.getLocalPort();
        }
        ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
        client.open();
        try {
            CountDownLatch reading = new CountDownLatch(1);
            Future<?> pendingRead = TASKS.submit(() -> {
                reading.countDown();
                expectIOException(client::read, "read awaiting a refused connection");
                return null;
            });
            reading.await();
            long started = System.nanoTime();
            expectIOException(() -> client.send(bytes("refused"), new InetSocketAddress(LOOPBACK, unusedPort)), "refused connection");
            check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 3, "refused connection waited for the full connect timeout");
            await(pendingRead);
            check(client.isClosed(), "adapter did not close after connection failure");
        } finally {
            client.close();
        }
    }

    private static void clientAdapterRejectsChangedDestination() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 2, LOOPBACK)) {
            ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
            client.open();
            try {
                client.send(bytes("first destination"), new InetSocketAddress(LOOPBACK, listener.getLocalPort()));
                try (Socket accepted = listener.accept()) {
                    accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                    assertBytes(bytes("first destination"), TcpFrameCodec.read(accepted.getInputStream()), "first destination");
                    int otherPort = listener.getLocalPort() == 65535 ? 65534 : listener.getLocalPort() + 1;
                    expectIOException(() -> client.send(bytes("other destination"), new InetSocketAddress(LOOPBACK, otherPort)), "changed destination");
                    assertPeerClosed(accepted, "changed destination rejection");
                }
            } finally {
                client.close();
            }
        }
    }

    private static void clientAdapterRemoteEof() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 2, LOOPBACK)) {
            ClientVoicechatSocketImpl client = new ClientVoicechatSocketImpl();
            client.open();
            CountDownLatch sent = new CountDownLatch(1);
            Future<?> server = TASKS.submit(() -> {
                try (Socket ignored = listener.accept()) {
                    sent.await();
                    return null;
                }
            });
            client.send(bytes("before eof"), new InetSocketAddress(LOOPBACK, listener.getLocalPort()));
            sent.countDown();
            expectIOException(client::read, "remote EOF");
            await(server);
            check(client.isClosed(), "adapter remained open after remote EOF");
            client.close();
        }
    }

    private static TcpServer openServer() throws Exception {
        TcpServer server = new TcpServer();
        server.open(0, LOOPBACK.getHostAddress());
        check(!server.isClosed(), "opened server reports closed");
        check(server.getLocalPort() > 0, "server did not bind an ephemeral port");
        return server;
    }

    private static Socket connect(TcpServer server) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, server.getLocalPort()), TIMEOUT_SECONDS * 1000);
        socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void verifyEcho(TcpServer server, Socket client, String value) throws Exception {
        byte[] expected = bytes(value);
        TcpFrameCodec.write(client.getOutputStream(), expected);
        var packet = server.read();
        assertBytes(expected, packet.data(), "server receive");
        check(client.getLocalSocketAddress().equals(packet.address()), "echo routed from wrong client");
        server.send(packet.data(), packet.address());
        assertBytes(expected, TcpFrameCodec.read(client.getInputStream()), "server reply");
    }

    private static void assertPeerClosed(Socket socket, String description) throws IOException {
        try {
            check(socket.getInputStream().read() == -1, description + " peer sent unexpected bytes instead of closing");
        } catch (SocketException expected) {
            // A peer may close gracefully (EOF) or reset while disposing of invalid data.
        }
    }

    private static void expectReadTermination(Callable<?> read, String description) throws Exception {
        try {
            check(read.call() == null, description + " returned data instead of terminating");
        } catch (IOException expected) {
            // Both clean EOF and an exception are valid terminal read results.
        }
    }

    private static void expectIOException(CheckedRunnable action, String description) throws Exception {
        try {
            action.run();
            throw new AssertionError(description + " did not throw an IOException");
        } catch (IOException expected) {
            // Expected rejection.
        }
    }

    private static byte[] writerPayload(int id) {
        byte[] packet = payload(16 + (id % 200), id);
        ByteBuffer.wrap(packet).putInt(id);
        return packet;
    }

    private static byte[] header(int length) {
        return ByteBuffer.allocate(4).putInt(length).array();
    }

    private static byte[] frame(byte[] packet) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        TcpFrameCodec.write(encoded, packet);
        return encoded.toByteArray();
    }

    private static byte[] payload(int size, int seed) {
        byte[] packet = new byte[size];
        for (int index = 0; index < packet.length; index++) {
            packet[index] = (byte) (seed + 31 * index);
        }
        return packet;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertBytes(byte[] expected, byte[] actual, String description) {
        check(Arrays.equals(expected, actual), description + " payload differs");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (exception.getCause() instanceof Error cause) {
                throw cause;
            }
            throw exception;
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new AssertionError("operation did not complete within " + TIMEOUT_SECONDS + " seconds", exception);
        }
    }

    private static void run(String name, CheckedRunnable test) {
        long start = System.nanoTime();
        Future<?> execution = TASKS.submit(() -> {
            test.run();
            return null;
        });
        try {
            await(execution);
            passed++;
            System.out.printf("PASS %s (%d ms)%n", name, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL " + name);
            failure.printStackTrace(System.err);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private record SocketPair(Socket client, Socket accepted) implements AutoCloseable {

        static SocketPair create() throws IOException {
            try (ServerSocket listener = new ServerSocket(0, 4, LOOPBACK)) {
                Socket client = new Socket(LOOPBACK, listener.getLocalPort());
                Socket accepted = listener.accept();
                client.setSoTimeout(TIMEOUT_SECONDS * 1000);
                accepted.setSoTimeout(TIMEOUT_SECONDS * 1000);
                client.setTcpNoDelay(true);
                accepted.setTcpNoDelay(true);
                return new SocketPair(client, accepted);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                client.close();
            } finally {
                accepted.close();
            }
        }
    }
}
