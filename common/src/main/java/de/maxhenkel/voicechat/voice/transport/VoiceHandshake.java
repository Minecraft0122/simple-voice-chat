package de.maxhenkel.voicechat.voice.transport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Connection-bound authentication and HKDF-SHA256 directional traffic keys. */
public final class VoiceHandshake {
    public static final int SIZE = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] challenge;
    private final byte[] proof;
    private final byte[] clientKey;
    private final byte[] serverKey;
    private final long createdAt = System.nanoTime();

    public static VoiceHandshake create(byte[] secret, UUID player) {
        byte[] challenge = new byte[SIZE];
        RANDOM.nextBytes(challenge);
        return new VoiceHandshake(secret, player, challenge);
    }

    public VoiceHandshake(byte[] secret, UUID player, byte[] challenge) {
        if (secret.length != 16 || challenge.length != SIZE) throw new IllegalArgumentException("Invalid handshake");
        this.challenge = challenge.clone();
        ByteBuffer context = ByteBuffer.allocate(16 + SIZE);
        context.putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits()).put(challenge);
        // HKDF extract, then independent expand labels bind identity and direction.
        byte[] prk = hmac(challenge, secret);
        proof = expand(prk, "voicechat/1021/auth", context.array());
        clientKey = Arrays.copyOf(expand(prk, "voicechat/1021/client", context.array()), 16);
        serverKey = Arrays.copyOf(expand(prk, "voicechat/1021/server", context.array()), 16);
        Arrays.fill(prk, (byte) 0);
    }

    private static byte[] expand(byte[] key, String label, byte[] context) {
        byte[] name = label.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer info = ByteBuffer.allocate(name.length + context.length + 1);
        info.put(name).put(context).put((byte) 1);
        return hmac(key, info.array());
    }

    private static byte[] hmac(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public boolean verify(byte[] response) {
        return !expired() && MessageDigest.isEqual(proof, response);
    }

    public boolean expired() { return System.nanoTime() - createdAt > 10_000_000_000L; }
    public byte[] challenge() { return challenge.clone(); }
    public byte[] proof() { return proof.clone(); }
    public byte[] clientKey() { return clientKey.clone(); }
    public byte[] serverKey() { return serverKey.clone(); }
}
