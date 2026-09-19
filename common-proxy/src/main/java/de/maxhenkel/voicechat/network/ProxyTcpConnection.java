package de.maxhenkel.voicechat.network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** One framed client connection owned by the central voice proxy. */
final class ProxyTcpConnection implements AutoCloseable {

    private static final int MAX_FRAME_SIZE = 4096;
    private static final int QUEUE_SIZE = 128;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread writer;

    ProxyTcpConnection(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(30_000);
        input = socket.getInputStream();
        output = socket.getOutputStream();
        writer = Thread.ofVirtual().name("voicechat-proxy-tcp-writer").unstarted(this::writeLoop);
        writer.start();
    }

    byte[] read() throws IOException {
        if (isClosed()) throw new IOException("Voice proxy connection is closed");
        int length = readInt(input);
        if (length <= 0 || length > MAX_FRAME_SIZE) throw new IOException("Invalid voice proxy frame length");
        byte[] frame = input.readNBytes(length);
        if (frame.length != length) throw new EOFException("Truncated voice proxy frame");
        return frame;
    }

    void send(byte[] frame) throws IOException {
        if (frame == null || frame.length <= 0 || frame.length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid voice proxy frame length");
        }
        if (closed.get() || !outbound.offer(Arrays.copyOf(frame, frame.length))) {
            close();
            throw new IOException("Voice proxy connection is closed or congested");
        }
    }

    boolean isClosed() {
        return closed.get() || socket.isClosed();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        outbound.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (Thread.currentThread() != writer) writer.interrupt();
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = outbound.take();
                synchronized (output) {
                    output.write((frame.length >>> 24) & 0xFF);
                    output.write((frame.length >>> 16) & 0xFF);
                    output.write((frame.length >>> 8) & 0xFF);
                    output.write(frame.length & 0xFF);
                    output.write(frame);
                    output.flush();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            close();
        }
    }

    private static int readInt(InputStream input) throws IOException {
        int a = input.read();
        int b = input.read();
        int c = input.read();
        int d = input.read();
        if ((a | b | c | d) < 0) throw new EOFException("Truncated voice proxy frame header");
        return (a << 24) | (b << 16) | (c << 8) | d;
    }
}
