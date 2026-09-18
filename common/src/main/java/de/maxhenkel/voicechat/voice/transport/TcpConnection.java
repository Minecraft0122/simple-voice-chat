package de.maxhenkel.voicechat.voice.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A framed TCP connection with one reader and an asynchronous bounded writer. */
public final class TcpConnection implements AutoCloseable {

    private static final int OUTBOUND_QUEUE_CAPACITY = 64;
    private static final long FRAME_TIMEOUT_MILLIS = 10_000L;
    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;
    private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlineExecutor();

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread writer;

    public TcpConnection(Socket socket) throws IOException {
        this(socket, DEFAULT_READ_TIMEOUT_MILLIS);
    }

    /** Takes ownership of the socket. Zero disables the idle timeout, but not frame deadlines. */
    public TcpConnection(Socket socket, int idleTimeoutMillis) throws IOException {
        if (socket == null) {
            throw new IOException("TCP voice socket is null");
        }
        this.socket = socket;
        try {
            if (!socket.isConnected() || socket.isClosed()) {
                throw new IOException("TCP voice socket is not connected");
            }
            if (idleTimeoutMillis < 0) {
                throw new IOException("TCP voice idle timeout must not be negative");
            }
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(idleTimeoutMillis);
            input = socket.getInputStream();
            output = socket.getOutputStream();
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        // Assign before starting: an immediately failing writer may call close().
        writer = Thread.ofVirtual().name("voicechat-tcp-writer").unstarted(this::runWriter);
        writer.start();
    }

    public byte[] read() throws IOException {
        if (isClosed()) {
            throw new IOException("TCP voice connection is closed");
        }
        Deadline deadline = null;
        try {
            // Idle peers get the configured timeout. Once a frame starts, even a
            // peer sending one byte at a time must finish it within ten seconds.
            int firstByte = input.read();
            if (firstByte < 0) {
                throw new java.io.EOFException("TCP voice connection closed");
            }
            deadline = new Deadline();
            return TcpFrameCodec.read(input, firstByte);
        } catch (IOException e) {
            close();
            if (deadline != null && deadline.expired.get()) {
                SocketTimeoutException timeout = new SocketTimeoutException("TCP voice frame read timed out");
                timeout.initCause(e);
                throw timeout;
            }
            throw e;
        } finally {
            if (deadline != null) {
                deadline.cancel();
            }
        }
    }

    /** Queues a copy without blocking on the remote peer. */
    public void send(byte[] data) throws IOException {
        TcpFrameCodec.checkLength(data);
        synchronized (outbound) {
            if (isClosed()) {
                throw new IOException("TCP voice connection is closed");
            }
            if (outbound.offer(Arrays.copyOf(data, data.length))) {
                return;
            }
        }
        close();
        throw new IOException("TCP voice outbound queue is full");
    }

    private void runWriter() {
        try {
            while (!closed.get()) {
                byte[] data = outbound.take();
                Deadline deadline = new Deadline();
                try {
                    TcpFrameCodec.write(output, data);
                } finally {
                    deadline.cancel();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Closing the socket wakes the owner reading this connection so it
            // can reconnect or remove this peer through the normal read path.
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        synchronized (outbound) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            outbound.clear();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (Thread.currentThread() != writer) {
            writer.interrupt();
        }
    }

    public boolean isClosed() {
        return closed.get() || socket.isClosed();
    }

    public SocketAddress getRemoteAddress() {
        return socket.getRemoteSocketAddress();
    }

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon().name("voicechat-tcp-deadlines").factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final class Deadline {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean expired = new AtomicBoolean();
        private final ScheduledFuture<?> future = DEADLINES.schedule(() -> {
            if (active.compareAndSet(true, false)) {
                expired.set(true);
                close();
            }
        }, FRAME_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

        private void cancel() {
            active.set(false);
            future.cancel(false);
        }
    }
}