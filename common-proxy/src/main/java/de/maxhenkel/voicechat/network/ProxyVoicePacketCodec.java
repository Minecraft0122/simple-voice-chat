package de.maxhenkel.voicechat.network;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Codec for the small voice packet subset needed by the central proxy. */
final class ProxyVoicePacketCodec {

    private static final byte MAGIC = (byte) 0xFF;
    private static final byte MIC_PACKET = 0x01;
    private static final byte PLAYER_SOUND_PACKET = 0x02;
    private static final byte GROUP_SOUND_PACKET = 0x03;
    private static final byte AUTHENTICATE_PACKET = 0x05;
    private static final byte AUTHENTICATE_ACK_PACKET = 0x06;
    private static final byte CONNECTION_CHECK_PACKET = 0x09;
    private static final byte CONNECTION_CHECK_ACK_PACKET = 0x0A;
    private static final int SECRET_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE = 16;
    private static final int MAX_AUDIO_SIZE = 1275;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProxyVoicePacketCodec() {
    }

    static DecodedPacket decode(byte[] raw, byte[] secret, UUID expectedPlayer) throws Exception {
        ByteBuffer outer = ByteBuffer.wrap(raw);
        if (outer.remaining() < 1 + 16 || outer.get() != MAGIC) {
            throw new IllegalArgumentException("Invalid voice packet magic");
        }
        UUID player = new UUID(outer.getLong(), outer.getLong());
        if (expectedPlayer != null && !expectedPlayer.equals(player)) {
            throw new IllegalArgumentException("Voice packet player UUID does not match the session");
        }
        byte[] encrypted = readBytes(outer, MAX_AUDIO_SIZE + IV_SIZE + TAG_SIZE + 64);
        byte[] plaintext = decrypt(encrypted, secret);
        ByteBuffer payload = ByteBuffer.wrap(plaintext);
        if (!payload.hasRemaining()) {
            throw new IllegalArgumentException("Empty voice packet");
        }
        byte type = payload.get();
        if (type == MIC_PACKET) {
            byte[] data = readBytes(payload, MAX_AUDIO_SIZE);
            if (payload.remaining() < Long.BYTES + 1) throw new IllegalArgumentException("Truncated microphone packet");
            long sequence = payload.getLong();
            boolean whispering = payload.get() != 0;
            return new DecodedPacket(player, type, data, sequence, whispering, null);
        }
        if (type == AUTHENTICATE_PACKET) {
            if (payload.remaining() < 16 + SECRET_SIZE) throw new IllegalArgumentException("Truncated authentication packet");
            UUID authPlayer = new UUID(payload.getLong(), payload.getLong());
            byte[] authSecret = new byte[SECRET_SIZE];
            payload.get(authSecret);
            return new DecodedPacket(authPlayer, type, null, 0L, false, authSecret);
        }
        if (type == 0x0C) {
            if (payload.remaining() != 32) throw new IllegalArgumentException("Invalid authentication proof");
            byte[] proof = new byte[32]; payload.get(proof);
            return new DecodedPacket(player, type, null, 0L, false, proof);
        }
        if (type == CONNECTION_CHECK_PACKET) {
            return new DecodedPacket(player, type, null, 0L, false, null);
        }
        if (type == 0x07) {
            if (payload.remaining() != 24) throw new IllegalArgumentException("Invalid pong payload");
            return new DecodedPacket(player, type, null, 0L, false, null, plaintext);
        }
        if (type == 0x08) {
            return new DecodedPacket(player, type, null, 0L, false, null);
        }
        throw new IllegalArgumentException("Unsupported proxy voice packet type: " + type);
    }

    static byte[] encodeChallenge(byte[] secret, byte[] challenge) throws Exception {
        return encodePayload(secret, ByteBuffer.allocate(33).put((byte) 0x0B).put(challenge).array());
    }

    static byte[] microphonePayload(DecodedPacket packet) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 5 + packet.audio().length + 9);
        buffer.put((byte) 0x01);
        putBytes(buffer, packet.audio());
        buffer.putLong(packet.sequence()).put((byte) (packet.whispering() ? 1 : 0));
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    static byte[] encodeControl(byte[] secret, byte type) throws Exception {
        return encodePayload(secret, new byte[]{type});
    }

    static byte[] encodeClientPacket(byte[] secret, UUID player, byte[] plaintext) throws Exception {
        byte[] encrypted = encryptPayload(secret, plaintext);
        ByteBuffer outer = ByteBuffer.allocate(1 + 16 + 5 + encrypted.length);
        outer.put(MAGIC);
        putUUID(outer, player);
        putBytes(outer, encrypted);
        return Arrays.copyOf(outer.array(), outer.position());
    }

    static byte[] encodePlayerSound(byte[] secret, UUID channelId, UUID sender, byte[] audio,
                                    long sequence, boolean whispering, float distance) throws Exception {
        if (audio == null || audio.length > MAX_AUDIO_SIZE) throw new IllegalArgumentException("Audio payload is too large");
        ByteBuffer payload = ByteBuffer.allocate(1 + 16 + 16 + 5 + audio.length + 8 + 4 + 1);
        payload.put(PLAYER_SOUND_PACKET);
        putUUID(payload, channelId);
        putUUID(payload, sender);
        putBytes(payload, audio);
        payload.putLong(sequence);
        payload.putFloat(distance);
        payload.put((byte) (whispering ? 1 : 0));
        return encodePayload(secret, Arrays.copyOf(payload.array(), payload.position()));
    }

    static byte[] encodeGroupSound(byte[] secret, UUID channelId, UUID sender, byte[] audio, long sequence) throws Exception {
        if (audio == null || audio.length > MAX_AUDIO_SIZE) throw new IllegalArgumentException("Audio payload is too large");
        ByteBuffer payload = ByteBuffer.allocate(1 + 16 + 16 + 5 + audio.length + 8 + 1);
        payload.put(GROUP_SOUND_PACKET);
        putUUID(payload, channelId);
        putUUID(payload, sender);
        putBytes(payload, audio);
        payload.putLong(sequence);
        payload.put((byte) 0);
        return encodePayload(secret, Arrays.copyOf(payload.array(), payload.position()));
    }

    static byte[] encodePayload(byte[] secret, byte[] plaintext) throws Exception {
        byte[] encrypted = encryptPayload(secret, plaintext);
        ByteBuffer outer = ByteBuffer.allocate(1 + 5 + encrypted.length);
        outer.put(MAGIC);
        putBytes(outer, encrypted);
        return Arrays.copyOf(outer.array(), outer.position());
    }

    private static byte[] encryptPayload(byte[] secret, byte[] plaintext) throws Exception {
        checkSecret(secret);
        byte[] iv = new byte[IV_SIZE];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        return concat(iv, encrypted);
    }

    private static byte[] decrypt(byte[] payload, byte[] secret) throws Exception {
        checkSecret(secret);
        if (payload.length < IV_SIZE + TAG_SIZE) throw new IllegalArgumentException("Ciphertext is too short");
        byte[] iv = Arrays.copyOf(payload, IV_SIZE);
        byte[] encrypted = Arrays.copyOfRange(payload, IV_SIZE, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted);
    }

    private static void checkSecret(byte[] secret) {
        if (secret == null || secret.length != SECRET_SIZE) throw new IllegalArgumentException("Invalid voice secret");
    }

    private static byte[] readBytes(ByteBuffer buffer, int max) {
        int length = readVarInt(buffer);
        if (length < 0 || length > max || length > buffer.remaining()) throw new IllegalArgumentException("Invalid voice byte array length");
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }

    private static void putBytes(ByteBuffer buffer, byte[] data) {
        putVarInt(buffer, data.length);
        buffer.put(data);
    }

    private static int readVarInt(ByteBuffer buffer) {
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            if (!buffer.hasRemaining()) throw new IllegalArgumentException("Truncated varint");
            int value = buffer.get() & 0xFF;
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IllegalArgumentException("Varint is too long");
    }

    private static void putVarInt(ByteBuffer buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.put((byte) value);
    }

    private static void putUUID(ByteBuffer buffer, UUID uuid) {
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    record DecodedPacket(UUID playerUUID, byte type, byte[] audio, long sequence, boolean whispering, byte[] authenticatedSecret, byte[] pong) {
        DecodedPacket(UUID playerUUID, byte type, byte[] audio, long sequence, boolean whispering, byte[] authenticatedSecret) {
            this(playerUUID, type, audio, sequence, whispering, authenticatedSecret, null);
        }
    }
}
