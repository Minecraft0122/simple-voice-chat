package de.maxhenkel.voicechat.voice.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/** TCP listener with bounded queues and independent readers and writers for each peer. */
public final class TcpServer implements AutoCloseable {

    private static final int INBOUND_QUEUE_CAPACITY = 4096;
    private static final int MAX_CONNECTIONS = 1024;
    private static final Incoming CLOSED = new Incoming(null, null);

    private final BlockingQueue<Incoming> inbound = new ArrayBlockingQueue<>(INBOUND_QUEUE_CAPACITY);
    private final ConcurrentHashMap<SocketAddress, TcpConnection> connections = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final int idleTimeoutMillis;
    private volatile boolean closed = true;
    private volatile ServerSocket serverSocket;
    private volatile IOException failure;

    public TcpServer() {
        this(30_000);
    }

    public TcpServer(int idleTimeoutMillis) {
        if (idleTimeoutMillis < 0) {
            throw new IllegalArgumentException("TCP voice idle timeout must not be negative");
        }
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    /** Opens this listener once. A failed bind can be retried on the same instance. */
    public void open(int port, String bindAddress) throws IOException {
        synchronized (lifecycleLock) {
            if (serverSocket != null) {
                throw new IllegalStateException("TCP voice server was already opened");
            }
            InetAddress address = bindAddress == null || bindAddress.isEmpty() ? null : InetAddress.getByName(bindAddress);
            ServerSocket listener = new ServerSocket();
            try {
                listener.bind(new InetSocketAddress(address, port));
            } catch (IOException | RuntimeException e) {
                try {
                    listener.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
            serverSocket = listener;
            closed = false;
            Thread.ofVirtual().name("voicechat-tcp-acceptor").start(() -> acceptLoop(listener));
        }
    }

    private void acceptLoop(ServerSocket listener) {
        try {
            while (!closed) {
                Socket socket = listener.accept();
                TcpConnection connection;
                synchronized (lifecycleLock) {
                    if (closed || connections.size() >= MAX_CONNECTIONS) {
                        socket.close();
                        continue;
                    }
                    try {
                        connection = new TcpConnection(socket, idleTimeoutMillis);
                    } catch (IOException e) {
                        // A peer may disconnect while its socket is being initialized.
                        // TcpConnection owns and closes that socket on failure.
                        continue;
                    }
                    SocketAddress address = connection.getRemoteAddress();
                    if (connections.putIfAbsent(address, connection) != null) {
                        connection.close();
                        continue;
                    }
                    Thread.ofVirtual().name("voicechat-tcp-reader").start(() -> readLoop(connection));
                }
            }
        } catch (IOException e) {
            synchronized (lifecycleLock) {
                if (!closed) {
                    failure = e;
                }
            }
        } finally {
            // A permanent accept failure stops the listener and wakes read().
            close();
        }
    }

    private void readLoop(TcpConnection connection) {
        SocketAddress address = connection.getRemoteAddress();
        try {
            while (!connection.isClosed() && !closed) {
                byte[] data = connection.read();
                ReceivedPacket packet = new ReceivedPacket(data, address, System.currentTimeMillis());
                if (!inbound.offer(new Incoming(connection, packet))) {
                    // Disconnect an overloading sender rather than block all clients.
                    break;
                }
            }
        } catch (IOException ignored) {
            // Peer closure, malformed frames and timeouts affect only this peer.
        } finally {
            connections.remove(address, connection);
            connection.close();
        }
    }

    public ReceivedPacket read() throws IOException, InterruptedException {
        if (serverSocket == null) {
            throw new IOException("TCP voice server has not been opened");
        }
        while (true) {
            if (closed) {
                throw closedException();
            }
            Incoming incoming = inbound.take();
            if (incoming == CLOSED || closed) {
                // A persistent sentinel also wakes concurrent or subsequent readers.
                inbound.offer(CLOSED);
                throw closedException();
            }
            ReceivedPacket packet = incoming.packet();
            // Do not deliver packets queued by a closed or replaced connection,
            // even when a new peer later reuses the same remote address and port.
            if (connections.get(packet.address()) == incoming.connection() && !incoming.connection().isClosed()) {
                return packet;
            }
        }
    }

    private IOException closedException() {
        IOException reason = failure;
        return reason == null ? new IOException("TCP voice server is closed") : new IOException("TCP voice listener failed", reason);
    }

    public void send(byte[] data, SocketAddress address) throws IOException {
        TcpConnection connection = connections.get(address);
        if (closed || connection == null) {
            return;
        }
        try {
            connection.send(data);
        } catch (IOException e) {
            connections.remove(address, connection);
            connection.close();
            throw e;
        }
    }

    public void closeConnection(SocketAddress address) {
        TcpConnection connection = connections.remove(address);
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void close() {
        List<TcpConnection> toClose;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            toClose = new ArrayList<>(connections.values());
            connections.clear();
            inbound.clear();
            inbound.offer(CLOSED);
        }
        for (TcpConnection connection : toClose) {
            connection.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public int getLocalPort() {
        ServerSocket listener = serverSocket;
        return listener == null ? -1 : listener.getLocalPort();
    }

    public record ReceivedPacket(byte[] data, SocketAddress address, long timestamp) {
    }

    private record Incoming(TcpConnection connection, ReceivedPacket packet) {
    }
}
