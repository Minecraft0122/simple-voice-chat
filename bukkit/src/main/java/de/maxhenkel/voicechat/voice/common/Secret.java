package de.maxhenkel.voicechat.voice.common;

import io.netty.buffer.ByteBuf;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

public class Secret {

    public static final int SECRET_SIZE_BYTES = 16;
    public static final int IV_SIZE_BYTES = 12;
    public static final int TAG_LEN_BITS = 128;
    public static final String CIPHER = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final SecretKeySpec keySpec;

    protected Secret(byte[] secret) {
        if (secret == null || secret.length != SECRET_SIZE_BYTES) {
            throw new IllegalArgumentException("Voice chat secret must contain exactly " + SECRET_SIZE_BYTES + " bytes");
        }
        this.secret = Arrays.copyOf(secret, secret.length);
        this.keySpec = new SecretKeySpec(this.secret, "AES");
    }

    public static Secret generateNewRandomSecret() {
        byte[] secret = new byte[SECRET_SIZE_BYTES];
        RANDOM.nextBytes(secret);
        return new Secret(secret);
    }

    public static Secret fromBytes(byte[] secret) {
        return new Secret(secret);
    }

    public static Secret fromBytes(ByteBuf buf) {
        byte[] secretBytes = new byte[SECRET_SIZE_BYTES];
        buf.readBytes(secretBytes);
        return Secret.fromBytes(secretBytes);
    }

    public void toBytes(ByteBuf buf) {
        buf.writeBytes(secret);
    }

    public byte[] getSecret() {
        return Arrays.copyOf(secret, secret.length);
    }

    public SecretKeySpec getKeySpec() {
        return keySpec;
    }

    public static byte[] generateIV() {
        byte[] iv = new byte[IV_SIZE_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    public byte[] encrypt(byte[] data) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        byte[] iv = generateIV();
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, getKeySpec(), new GCMParameterSpec(TAG_LEN_BITS, iv));
        byte[] enc = cipher.doFinal(data);
        byte[] payload = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(enc, 0, payload, iv.length, enc.length);
        return payload;
    }

    public byte[] decrypt(byte[] payload) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        if (payload == null || payload.length < IV_SIZE_BYTES + TAG_LEN_BITS / Byte.SIZE) {
            throw new IllegalArgumentException("Voice chat ciphertext is shorter than the GCM IV and tag");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_SIZE_BYTES);
        byte[] data = Arrays.copyOfRange(payload, IV_SIZE_BYTES, payload.length);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, getKeySpec(), new GCMParameterSpec(TAG_LEN_BITS, iv));
        return cipher.doFinal(data);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Secret)) {
            return false;
        }
        return MessageDigest.isEqual(secret, ((Secret) o).secret);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(secret);
    }
}
