package de.maxhenkel.voicechat.voice.transport;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Messages on proxy-owned Minecraft channels. Client-originated copies must be blocked. */
public final class ProxyMessages {
    public static final int CONNECTION = 0;
    public static final int MICROPHONE = 1;
    public static final int SOUND = 2;
    public static final int PONG = 3;
    private ProxyMessages() {}

    public static byte[] encode(int type, long generation, UUID session, byte[] payload) {
        if (payload.length > 4096) throw new IllegalArgumentException("Oversized proxy message");
        return ByteBuffer.allocate(25 + payload.length).put((byte) type).putLong(generation)
                .putLong(session.getMostSignificantBits()).putLong(session.getLeastSignificantBits()).put(payload).array();
    }

    public static Message decode(byte[] bytes) {
        if (bytes.length < 25 || bytes.length > 4121) throw new IllegalArgumentException("Invalid proxy message length");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int type = buffer.get() & 255;
        long generation = buffer.getLong();
        UUID session = new UUID(buffer.getLong(), buffer.getLong());
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new Message(type, generation, session, payload);
    }

    public record Message(int type, long generation, UUID session, byte[] payload) {}
}
