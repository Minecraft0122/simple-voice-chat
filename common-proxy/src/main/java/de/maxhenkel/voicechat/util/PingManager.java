package de.maxhenkel.voicechat.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** TCP diagnostic probe for the central voice endpoint. */
public class PingManager {

    private static final UUID CHECK_V1 = UUID.fromString("58bc9ae9-c7a8-45e4-a11c-efbb67199425");
    private static final byte MAGIC_BYTE = (byte) 0xFF;
    private static final int INTERVAL = 1000;
    private static final int MAX_FRAME_SIZE = 4096;

    public static void sendPing(SocketAddress address, int port, int attempts, PingListener listener) {
        new PingThread((InetSocketAddress) address, port, attempts, listener).start();
    }

    private static class PingThread extends Thread {
        private final InetSocketAddress address;
        private final int port;
        private final int totalAttempts;
        private final PingListener listener;

        private PingThread(InetSocketAddress address, int port, int totalAttempts, PingListener listener) {
            this.address = address;
            this.port = port;
            this.totalAttempts = totalAttempts;
            this.listener = listener;
            setDaemon(true);
            setName("TcpVoiceChatPingThread");
        }

        @Override
        public void run() {
            int timeoutCount = 0;
            int successCount = 0;
            int lowestPing = -1;
            for (int i = 0; i < totalAttempts; i++) {
                try {
                    listener.onSend(i + 1);
                    long sentAt = System.currentTimeMillis();
                    try (Socket socket = new Socket()) {
                        socket.setTcpNoDelay(true);
                        socket.setSoTimeout(INTERVAL);
                        socket.connect(address.isUnresolved()
                                ? new InetSocketAddress(address.getHostString(), port)
                                : new InetSocketAddress(address.getAddress(), port), INTERVAL);
                        writeFrame(socket.getOutputStream(), buildRequest(UUID.randomUUID(), sentAt));
                        readFrame(socket.getInputStream());
                    }
                    int ping = (int) (System.currentTimeMillis() - sentAt);
                    listener.onSuccessfulAttempt(i + 1, ping);
                    successCount++;
                    if (lowestPing < 0 || ping < lowestPing) lowestPing = ping;
                } catch (SocketTimeoutException e) {
                    listener.onFailedAttempt(i + 1);
                    timeoutCount++;
                } catch (Exception e) {
                    listener.onError(e);
                    return;
                }
                if (i + 1 < totalAttempts) {
                    try {
                        Thread.sleep(INTERVAL);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            listener.onFinish(successCount, timeoutCount, lowestPing);
        }
    }

    private static byte[] buildRequest(UUID id, long timestamp) {
        ByteBuffer payload = ByteBuffer.allocate(24);
        payload.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(timestamp);
        byte[] bytes = payload.array();
        ByteBuffer packet = ByteBuffer.allocate(1 + 16 + 5 + bytes.length);
        packet.put(MAGIC_BYTE).putLong(CHECK_V1.getMostSignificantBits()).putLong(CHECK_V1.getLeastSignificantBits());
        putVarInt(packet, bytes.length).put(bytes);
        return Arrays.copyOf(packet.array(), packet.position());
    }

    private static void writeFrame(OutputStream output, byte[] data) throws IOException {
        if (data.length <= 0 || data.length > MAX_FRAME_SIZE) throw new IOException("Invalid TCP ping frame");
        output.write((data.length >>> 24) & 0xFF);
        output.write((data.length >>> 16) & 0xFF);
        output.write((data.length >>> 8) & 0xFF);
        output.write(data.length & 0xFF);
        output.write(data);
        output.flush();
    }

    private static byte[] readFrame(InputStream input) throws IOException {
        int a = input.read(), b = input.read(), c = input.read(), d = input.read();
        if ((a | b | c | d) < 0) throw new EOFException("Truncated TCP ping response frame");
        int length = (a << 24) | (b << 16) | (c << 8) | d;
        if (length <= 0 || length > MAX_FRAME_SIZE) throw new IOException("Invalid TCP ping response frame");
        byte[] result = input.readNBytes(length);
        if (result.length != length) throw new EOFException("Truncated TCP ping response");
        return result;
    }

    private static ByteBuffer putVarInt(ByteBuffer buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        return buffer.put((byte) value);
    }

    public interface PingListener {
        void onSend(int attempts);
        void onSuccessfulAttempt(int attempts, long pingMilliseconds);
        void onFailedAttempt(int attempts);
        void onFinish(int successfulAttempts, int timeoutAttempts, long pingMilliseconds);
        void onError(Exception e);
    }
}
