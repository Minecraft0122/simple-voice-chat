package de.maxhenkel.voicechat.network;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

public final class ProxyVoicePacketCodecTest {

    public static void main(String[] args) throws Exception {
        byte[] secret = new byte[16];
        for (int i = 0; i < secret.length; i++) secret[i] = (byte) (i + 1);
        UUID player = UUID.randomUUID();

        byte[] audio = new byte[]{1, 2, 3, 4, 5};
        ByteBuffer mic = ByteBuffer.allocate(1 + 5 + audio.length + Long.BYTES + 1);
        mic.put((byte) 0x01);
        putBytes(mic, audio);
        mic.putLong(42L);
        mic.put((byte) 1);

        ProxyVoicePacketCodec.DecodedPacket decoded = ProxyVoicePacketCodec.decode(
                ProxyVoicePacketCodec.encodeClientPacket(secret, player, Arrays.copyOf(mic.array(), mic.position())),
                secret, player
        );
        check(decoded.type() == 0x01, "microphone packet type");
        check(decoded.sequence() == 42L, "microphone sequence");
        check(decoded.whispering(), "microphone whisper flag");
        check(Arrays.equals(decoded.audio(), audio), "microphone audio");

        byte[] authPlaintext = ByteBuffer.allocate(1 + 16 + 16)
                .put((byte) 0x05)
                .putLong(player.getMostSignificantBits())
                .putLong(player.getLeastSignificantBits())
                .put(secret)
                .array();
        ProxyVoicePacketCodec.DecodedPacket auth = ProxyVoicePacketCodec.decode(
                ProxyVoicePacketCodec.encodeClientPacket(secret, player, authPlaintext), secret, player
        );
        check(auth.type() == 0x05, "authentication packet type");
        check(Arrays.equals(auth.authenticatedSecret(), secret), "authentication secret");

        byte[] tampered = ProxyVoicePacketCodec.encodeClientPacket(secret, player, Arrays.copyOf(mic.array(), mic.position()));
        tampered[tampered.length - 1] ^= 1;
        expectFailure(() -> ProxyVoicePacketCodec.decode(tampered, secret, player), "GCM tamper detection");

        byte[] wrongPlayer = ProxyVoicePacketCodec.encodeClientPacket(secret, player, Arrays.copyOf(mic.array(), mic.position()));
        expectFailure(() -> ProxyVoicePacketCodec.decode(wrongPlayer, secret, UUID.randomUUID()), "outer UUID binding");

        byte[] outgoing = ProxyVoicePacketCodec.encodePlayerSound(secret, player, player, audio, 43L, false, 48F);
        check(outgoing[0] == (byte) 0xFF, "server packet magic");
        check(readVarInt(outgoing, 1) == outgoing.length - 2, "server packet length prefix");
        System.out.println("ProxyVoicePacketCodecTest passed");
    }

    private static void putBytes(ByteBuffer buffer, byte[] bytes) {
        buffer.put((byte) bytes.length);
        buffer.put(bytes);
    }

    private static int readVarInt(byte[] bytes, int offset) {
        int value = 0;
        int shift = 0;
        int index = offset;
        while (shift < 35) {
            int current = bytes[index++] & 0xFF;
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) return value;
            shift += 7;
        }
        throw new AssertionError("varint too long");
    }

    private static void expectFailure(ThrowingRunnable action, String name) throws Exception {
        try {
            action.run();
        } catch (GeneralSecurityException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(name + " was accepted");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
