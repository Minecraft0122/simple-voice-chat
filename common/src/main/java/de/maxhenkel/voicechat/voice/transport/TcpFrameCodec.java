package de.maxhenkel.voicechat.voice.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Length-prefixed framing used by the TCP voice transport. */
public final class TcpFrameCodec {

    public static final int MAX_FRAME_SIZE = 4096;

    private TcpFrameCodec() {
    }

    public static byte[] read(InputStream input) throws IOException {
        int firstByte = input.read();
        if (firstByte < 0) {
            throw new EOFException("TCP voice connection closed");
        }
        return read(input, firstByte);
    }

    static byte[] read(InputStream input, int firstByte) throws IOException {
        byte[] header = new byte[Integer.BYTES];
        header[0] = (byte) firstByte;
        readFully(input, header, 1, header.length - 1);
        int length = ((header[0] & 0xff) << 24)
                | ((header[1] & 0xff) << 16)
                | ((header[2] & 0xff) << 8)
                | (header[3] & 0xff);
        if (length < 1 || length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid TCP voice frame length: " + length);
        }
        byte[] data = new byte[length];
        readFully(input, data, 0, length);
        return data;
    }

    public static void write(OutputStream output, byte[] data) throws IOException {
        checkLength(data);
        byte[] frame = new byte[Integer.BYTES + data.length];
        frame[0] = (byte) (data.length >>> 24);
        frame[1] = (byte) (data.length >>> 16);
        frame[2] = (byte) (data.length >>> 8);
        frame[3] = (byte) data.length;
        System.arraycopy(data, 0, frame, Integer.BYTES, data.length);
        output.write(frame);
        output.flush();
    }

    static void checkLength(byte[] data) throws IOException {
        if (data == null || data.length < 1 || data.length > MAX_FRAME_SIZE) {
            throw new IOException("Invalid TCP voice frame length: " + (data == null ? -1 : data.length));
        }
    }

    private static void readFully(InputStream input, byte[] target, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(target, offset + read, length - read);
            if (count < 0) {
                throw new EOFException("TCP voice connection closed while reading a frame");
            }
            if (count == 0) {
                int one = input.read();
                if (one < 0) {
                    throw new EOFException("TCP voice connection closed while reading a frame");
                }
                target[offset + read++] = (byte) one;
            } else {
                read += count;
            }
        }
    }
}
