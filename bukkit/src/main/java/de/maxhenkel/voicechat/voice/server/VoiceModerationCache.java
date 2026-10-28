package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.voice.common.MicPacket;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Disk queue for an administrator's local voice moderation program.
 * The queue deliberately uses ordinary files so a second server process can consume it
 * without a plugin API or a network service.
 */
public final class VoiceModerationCache {
    private static final long SEGMENT_MILLIS = 60_000L;
    private final Path root;
    private final Path segments;
    private final Path priorityQueue;
    private final Path periodicQueue;

    public VoiceModerationCache(Path configDirectory) {
        root = configDirectory.resolve("moderation");
        segments = root.resolve("segments");
        priorityQueue = root.resolve("queue").resolve("priority");
        periodicQueue = root.resolve("queue").resolve("periodic");
    }

    public synchronized void record(UUID player, MicPacket packet) {
        if (!Voicechat.SERVER_CONFIG.voiceModerationCacheEnabled.get() || packet.getData() == null || packet.getData().length == 0) return;
        try {
            Files.createDirectories(segments);
            long minute = System.currentTimeMillis() / SEGMENT_MILLIS;
            Path file = segments.resolve(player + "-" + minute + ".vcm");
            try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                 DataOutputStream data = new DataOutputStream(output)) {
                data.writeLong(System.currentTimeMillis());
                data.writeLong(packet.getSequenceNumber());
                data.writeBoolean(packet.isWhispering());
                data.writeInt(packet.getData().length);
                data.write(packet.getData());
            }
        } catch (IOException e) {
            Voicechat.LOGGER.warn("Could not cache voice content for moderation", e);
        }
    }

    /** Enqueues the recent segment immediately; a consumer should watch queue/priority. */
    public synchronized boolean report(UUID player, UUID reporter, String reason) {
        if (!Voicechat.SERVER_CONFIG.voiceModerationCacheEnabled.get()) return false;
        long cutoff = System.currentTimeMillis() - SEGMENT_MILLIS;
        List<String> files = segmentNames(player, cutoff);
        if (files.isEmpty()) return false;
        return writeTask(priorityQueue, "report", player, reporter, reason, files);
    }

    /** Adds old, unqueued segments to the lower-priority queue once per segment. */
    public synchronized void enqueuePeriodic() {
        if (!Voicechat.SERVER_CONFIG.voiceModerationCacheEnabled.get()) return;
        long cutoff = (System.currentTimeMillis() / SEGMENT_MILLIS - 1) * SEGMENT_MILLIS;
        try {
            Files.createDirectories(periodicQueue);
            Files.createDirectories(segments);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(segments, "*.vcm")) {
                for (Path segment : stream) {
                    if (Files.getLastModifiedTime(segment).toMillis() > cutoff) continue;
                    Path marker = periodicQueue.resolve(segment.getFileName() + ".queued");
                    if (Files.exists(marker)) continue;
                    UUID player = playerFromSegment(segment);
                    if (player != null) writeTask(periodicQueue, "periodic", player, null, "scheduled", List.of(segment.getFileName().toString()));
                }
            }
        } catch (IOException e) {
            Voicechat.LOGGER.warn("Could not enqueue periodic voice moderation tasks", e);
        }
    }

    public synchronized void cleanup() {
        long cutoff = System.currentTimeMillis() - Voicechat.SERVER_CONFIG.voiceModerationCacheHours.get() * 3_600_000L;
        try {
            deleteOlderThan(segments, cutoff, false);
            deleteOlderThan(priorityQueue, cutoff, true);
            deleteOlderThan(periodicQueue, cutoff, true);
            pruneSize();
        } catch (IOException e) {
            Voicechat.LOGGER.warn("Could not clean up voice moderation cache", e);
        }
    }

    public Path getRoot() { return root; }

    private List<String> segmentNames(UUID player, long cutoff) {
        List<String> result = new ArrayList<>();
        try {
            Files.createDirectories(segments);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(segments, player + "-*.vcm")) {
                for (Path file : stream) if (Files.getLastModifiedTime(file).toMillis() >= cutoff) result.add(file.getFileName().toString());
            }
        } catch (IOException e) {
            Voicechat.LOGGER.warn("Could not find recent voice moderation segments", e);
        }
        return result;
    }

    private boolean writeTask(Path queue, String kind, UUID player, UUID reporter, String reason, List<String> files) {
        try {
            Files.createDirectories(queue);
            UUID taskId = UUID.randomUUID();
            Path task = queue.resolve(taskId + ".json");
            StringBuilder json = new StringBuilder("{\n");
            field(json, "task_id", taskId.toString(), true);
            field(json, "kind", kind, true);
            field(json, "player_uuid", player.toString(), true);
            if (reporter != null) field(json, "reporter_uuid", reporter.toString(), true);
            field(json, "reason", reason == null ? "" : reason, true);
            field(json, "created_at_millis", Long.toString(System.currentTimeMillis()), false);
            json.append(",\n  \"segments\": [");
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) json.append(", ");
                json.append('"').append(escape(files.get(i))).append('"');
            }
            json.append("]\n}\n");
            Path temporary = task.resolveSibling(task.getFileName() + ".tmp");
            Files.writeString(temporary, json.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temporary, task, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException ignored) { Files.move(temporary, task); }
            if ("periodic".equals(kind)) Files.createFile(periodicQueue.resolve(files.get(0) + ".queued"));
            return true;
        } catch (IOException e) {
            Voicechat.LOGGER.warn("Could not enqueue voice moderation task", e);
            return false;
        }
    }

    private static void field(StringBuilder json, String key, String value, boolean comma) {
        json.append("  \"").append(key).append("\": \"").append(escape(value)).append('"');
        if (comma) json.append(',');
        json.append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static UUID playerFromSegment(Path segment) {
        String name = segment.getFileName().toString();
        if (name.length() < 36) return null;
        try { return UUID.fromString(name.substring(0, 36)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static void deleteOlderThan(Path directory, long cutoff, boolean taskDirectory) throws IOException {
        if (!Files.exists(directory)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) if (Files.getLastModifiedTime(file).toMillis() < cutoff) Files.deleteIfExists(file);
        }
    }

    private void pruneSize() throws IOException {
        long max = Voicechat.SERVER_CONFIG.voiceModerationCacheMaxMb.get() * 1024L * 1024L;
        List<Path> files = new ArrayList<>();
        if (Files.exists(segments)) try (DirectoryStream<Path> stream = Files.newDirectoryStream(segments, "*.vcm")) { stream.forEach(files::add); }
        long total = files.stream().mapToLong(this::size).sum();
        files.sort(Comparator.comparingLong(this::modified));
        for (Path file : files) {
            if (total <= max) break;
            long fileSize = size(file);
            Files.deleteIfExists(file);
            total -= fileSize;
        }
    }

    private long size(Path path) { try { return Files.size(path); } catch (IOException e) { return 0L; } }
    private long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException e) { return Long.MAX_VALUE; } }
}
