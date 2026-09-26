import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.security.SecureRandom;
import java.util.Arrays;

/** Local comparison, not a portable claim about all CPUs. Matches the per-packet JCA usage. */
public class CryptoBenchmark {
    private static final SecureRandom random = new SecureRandom();
    private static volatile int sink;
    private static long sample(String algorithm, byte[] data, byte[] key, int iterations) throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            boolean aes = algorithm.startsWith("AES");
            var spec = aes ? new GCMParameterSpec(128, nonce) : new IvParameterSpec(nonce);
            var secret = new SecretKeySpec(key, aes ? "AES" : "ChaCha20");
            Cipher encrypt = Cipher.getInstance(algorithm);
            encrypt.init(Cipher.ENCRYPT_MODE, secret, spec);
            byte[] encrypted = encrypt.doFinal(data);
            Cipher decrypt = Cipher.getInstance(algorithm);
            decrypt.init(Cipher.DECRYPT_MODE, secret, spec);
            byte[] decoded = decrypt.doFinal(encrypted);
            if (decoded.length != data.length) throw new AssertionError();
            sink ^= decoded[0];
        }
        return (System.nanoTime() - start) / iterations;
    }
    public static void main(String[] args) throws Exception {
        String[] algorithms = {"AES/GCM/NoPadding", "ChaCha20-Poly1305"};
        byte[][] keys = {new byte[16], new byte[32]};
        for (byte[] key : keys) random.nextBytes(key);
        System.out.println("Java " + System.getProperty("java.version") + ", " + System.getProperty("os.arch"));
        for (int size : new int[]{128, 512, 1275}) {
            byte[] data = new byte[size]; random.nextBytes(data);
            for (int algorithm = 0; algorithm < 2; algorithm++) sample(algorithms[algorithm], data, keys[algorithm], 10000);
            long[][] times = new long[2][5];
            for (int round = 0; round < 5; round++) {
                for (int turn = 0; turn < 2; turn++) {
                    int algorithm = (round + turn) % 2;
                    times[algorithm][round] = sample(algorithms[algorithm], data, keys[algorithm], 20000);
                }
            }
            for (int algorithm = 0; algorithm < 2; algorithm++) {
                Arrays.sort(times[algorithm]);
                System.out.printf("%d bytes | %s | median encrypt+decrypt %.3f us%n", size, algorithms[algorithm], times[algorithm][2] / 1000.0);
            }
        }
    }
}
