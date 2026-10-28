package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks voice payload rates and persists administrator mutes. */
public final class VoiceProtectionManager {
    private static final long METRICS_MAX_AGE_MILLIS = 3000L;
    private static final long WINDOW_MILLIS = 1000L;
    private final Path mutedPlayersFile;
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> talkerLeases = new ConcurrentHashMap<>();
    private long windowStartedAt = System.currentTimeMillis();
    private long inboundBytes;
    private long outboundBytes;
    private volatile long inboundBytesPerSecond;
    private volatile long outboundBytesPerSecond;
    private volatile long serverBytesPerSecond = -1L;
    private volatile long serverMetricsAgeMillis = Long.MAX_VALUE;
    private volatile boolean capacityBlocked;

    public VoiceProtectionManager(Path configDirectory) {
        mutedPlayersFile = configDirectory.resolve("muted-players.txt");
        loadMutedPlayers();
    }

    public synchronized void recordInbound(UUID player, int bytes) {
        rollWindow();
        inboundBytes += Math.max(0, bytes);
    }

    public synchronized void recordOutbound(int bytes) {
        rollWindow();
        outboundBytes += Math.max(0, bytes);
    }

    public boolean isMuted(UUID player) {
        return mutedPlayers.contains(player);
    }

    public synchronized void mute(UUID player) throws IOException {
        mutedPlayers.add(player);
        talkerLeases.remove(player);
        saveMutedPlayers();
    }

    public synchronized void unmute(UUID player) throws IOException {
        mutedPlayers.remove(player);
        saveMutedPlayers();
    }

    public List<UUID> getMutedPlayers() {
        return mutedPlayers.stream().sorted().toList();
    }

    public boolean tryAdmit(UUID player) {
        if (isMuted(player)) return false;
        long now = System.currentTimeMillis();
        Long lease = talkerLeases.get(player);
        if (lease != null && lease >= now) {
            talkerLeases.put(player, now + leaseMillis());
            return true;
        }
        if (isCapacityBlocked(now)) return false;
        talkerLeases.put(player, now + leaseMillis());
        return true;
    }

    public void release(UUID player) {
        talkerLeases.remove(player);
    }

    public int availability(UUID player) {
        if (isMuted(player)) return de.maxhenkel.voicechat.voice.transport.VoiceAvailability.MUTED;
        Long lease = talkerLeases.get(player);
        if (lease != null && lease >= System.currentTimeMillis()) return de.maxhenkel.voicechat.voice.transport.VoiceAvailability.AVAILABLE;
        return isCapacityBlocked(System.currentTimeMillis())
                ? de.maxhenkel.voicechat.voice.transport.VoiceAvailability.SERVER_BUSY
                : de.maxhenkel.voicechat.voice.transport.VoiceAvailability.AVAILABLE;
    }

    public BandwidthSnapshot snapshot() {
        rollWindow();
        return new BandwidthSnapshot(inboundBytesPerSecond, outboundBytesPerSecond,
                serverBytesPerSecond, serverMetricsAgeMillis, capacityBlocked, getMutedPlayers());
    }

    /** Reads a local monitor's atomic properties snapshot. Missing or stale data fails closed when a server cap is enabled. */
    public void refreshServerMetrics() {
        int limit = Voicechat.SERVER_CONFIG.serverBandwidthLimitMbps.get();
        if (limit <= 0) {
            serverBytesPerSecond = -1L;
            serverMetricsAgeMillis = Long.MAX_VALUE;
            evaluateCapacity(System.currentTimeMillis());
            return;
        }
        Path metrics = Path.of(Voicechat.SERVER_CONFIG.serverBandwidthMetricsFile.get());
        if (!metrics.isAbsolute()) metrics = Path.of("").toAbsolutePath().resolve(metrics);
        try (InputStream in = Files.newInputStream(metrics)) {
            Properties properties = new Properties();
            properties.load(in);
            long rx = Long.parseLong(properties.getProperty("rx_bytes_per_second"));
            long tx = Long.parseLong(properties.getProperty("tx_bytes_per_second"));
            long sampled = Long.parseLong(properties.getProperty("sampled_at_millis"));
            long age = Math.max(0L, System.currentTimeMillis() - sampled);
            serverMetricsAgeMillis = age;
            serverBytesPerSecond = age <= METRICS_MAX_AGE_MILLIS ? Math.max(0L, rx) + Math.max(0L, tx) : -1L;
        } catch (Exception e) {
            serverMetricsAgeMillis = Long.MAX_VALUE;
            serverBytesPerSecond = -1L;
        }
        evaluateCapacity(System.currentTimeMillis());
    }

    private synchronized void rollWindow() {
        long now = System.currentTimeMillis();
        if (now - windowStartedAt < WINDOW_MILLIS) return;
        long elapsed = Math.max(WINDOW_MILLIS, now - windowStartedAt);
        inboundBytesPerSecond = inboundBytes * 1000L / elapsed;
        outboundBytesPerSecond = outboundBytes * 1000L / elapsed;
        inboundBytes = 0L;
        outboundBytes = 0L;
        windowStartedAt = now;
        evaluateCapacity(now);
    }

    private synchronized void evaluateCapacity(long now) {
        boolean enabled = false;
        boolean exceeded = false;
        boolean recovered = true;

        int pluginLimit = Voicechat.SERVER_CONFIG.pluginBandwidthLimitMbps.get();
        if (pluginLimit > 0) {
            enabled = true;
            long limit = mbpsToBytes(pluginLimit);
            long current = inboundBytesPerSecond + outboundBytesPerSecond;
            exceeded |= current >= limit;
            recovered &= current < limit * Voicechat.SERVER_CONFIG.bandwidthRecoveryPercent.get() / 100L;
        }

        int serverLimit = Voicechat.SERVER_CONFIG.serverBandwidthLimitMbps.get();
        if (serverLimit > 0) {
            enabled = true;
            long limit = mbpsToBytes(serverLimit);
            if (serverBytesPerSecond < 0L || serverMetricsAgeMillis > METRICS_MAX_AGE_MILLIS) {
                exceeded = true;
                recovered = false;
            } else {
                exceeded |= serverBytesPerSecond >= limit;
                recovered &= serverBytesPerSecond < limit * Voicechat.SERVER_CONFIG.bandwidthRecoveryPercent.get() / 100L;
            }
        }

        if (!enabled) capacityBlocked = false;
        else if (exceeded) capacityBlocked = true;
        else if (capacityBlocked && recovered) capacityBlocked = false;

        talkerLeases.entrySet().removeIf(entry -> entry.getValue() < now || mutedPlayers.contains(entry.getKey()));
    }

    private boolean isCapacityBlocked(long now) {
        evaluateCapacity(now);
        return capacityBlocked;
    }

    private static long mbpsToBytes(int mbps) {
        return mbps * 1_000_000L / 8L;
    }

    private static long leaseMillis() {
        return Voicechat.SERVER_CONFIG.voiceTalkerLeaseSeconds.get() * 1000L;
    }

    private void loadMutedPlayers() {
        if (!Files.exists(mutedPlayersFile)) return;
        try {
            for (String line : Files.readAllLines(mutedPlayersFile)) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                try { mutedPlayers.add(UUID.fromString(value)); }
                catch (IllegalArgumentException ignored) { Voicechat.LOGGER.warn("Ignoring invalid UUID in {}", mutedPlayersFile); }
            }
        } catch (IOException e) {
            Voicechat.LOGGER.error("Could not load muted voice players", e);
        }
    }

    private void saveMutedPlayers() throws IOException {
        Files.createDirectories(mutedPlayersFile.getParent());
        Path temporary = mutedPlayersFile.resolveSibling(mutedPlayersFile.getFileName() + ".tmp");
        List<String> lines = new ArrayList<>();
        lines.add("# UUIDs muted from voice chat");
        mutedPlayers.stream().sorted().map(UUID::toString).forEach(lines::add);
        Files.write(temporary, lines);
        try { Files.move(temporary, mutedPlayersFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException e) { Files.move(temporary, mutedPlayersFile, StandardCopyOption.REPLACE_EXISTING); }
    }

    public record BandwidthSnapshot(long inboundBytesPerSecond, long outboundBytesPerSecond,
                                    long serverBytesPerSecond, long serverMetricsAgeMillis,
                                    boolean admissionBlocked, List<UUID> mutedPlayers) {}

}
