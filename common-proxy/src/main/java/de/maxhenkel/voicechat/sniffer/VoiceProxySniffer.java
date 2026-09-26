package de.maxhenkel.voicechat.sniffer;

import de.maxhenkel.voicechat.VoiceProxy;
import de.maxhenkel.voicechat.voice.transport.VoiceAvailability;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Accepts identity and routing only from the player's current backend connection. */
public class VoiceProxySniffer {
    private final VoiceProxy voiceProxy;
    private final Map<UUID, UUID> playerUUIDMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> backendPlayerMap = new ConcurrentHashMap<>();
    private final Map<UUID, String> backendIds = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> compatibilityVersionMap = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> secretMap = new ConcurrentHashMap<>();
    private final Map<UUID, RoutingState> routes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> requestedAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> retiredIdentities = new ConcurrentHashMap<>();
    private final Set<UUID> errors = ConcurrentHashMap.newKeySet();

    public VoiceProxySniffer(VoiceProxy proxy) { voiceProxy = proxy; }
    // Deliberately no UUID fallback: absence is an error, never an identity assertion.
    public UUID getMappedPlayerUUID(UUID backendPlayer) { return playerUUIDMap.get(backendPlayer); }
    public boolean recentlyDisconnected(UUID backendPlayer) {
        Long until = retiredIdentities.get(backendPlayer);
        return until != null && System.currentTimeMillis() < until;
    }
    public UUID getBackendPlayerUUID(UUID player) { return backendPlayerMap.get(player); }
    @Deprecated public InetSocketAddress getBackendSocket(UUID player) { return null; }
    @Deprecated public void resetBackendSocket(UUID player) {}

    public synchronized ByteBuffer onPluginMessage(String channel, boolean fromServer, ByteBuffer message, UUID player) throws IncompatibleVoiceChatException {
        if (!fromServer && VoiceProxy.isPrivateChannel(channel)) return ByteBuffer.allocate(0);
        if (!fromServer && channel.equals(VoiceProxy.REQUEST_SECRET_CHANNEL)) {
            if (message.remaining() != 4) throw new IncompatibleVoiceChatException("Invalid secret request");
            compatibilityVersionMap.put(player, message.getInt());
            requestedAt.put(player, System.currentTimeMillis());
            voiceProxy.availability(player, VoiceAvailability.WAITING);
            return null;
        }
        if (!fromServer) return null;
        try {
            if (channel.equals(VoiceProxy.SECRET_CHANNEL)) return handleSecret(message, player);
            if (channel.equals(VoiceProxy.PROXY_ROUTING_CHANNEL)) {
                handleRouting(message.slice(), player);
                return ByteBuffer.allocate(0);
            }
            if (channel.equals(VoiceProxy.PROXY_AUDIO_CHANNEL)) {
                byte[] bytes = new byte[message.remaining()]; message.get(bytes);
                voiceProxy.relayAudio(player, bytes);
                return ByteBuffer.allocate(0);
            }
            if (channel.equals(VoiceProxy.PROXY_CONTROL_CHANNEL)) return ByteBuffer.allocate(0);
        } catch (RuntimeException e) {
            internalError(player, e.getMessage());
            throw new IncompatibleVoiceChatException("Invalid backend voice metadata", e);
        }
        return null;
    }

    private ByteBuffer handleSecret(ByteBuffer message, UUID player) throws IncompatibleVoiceChatException {
        Integer version = compatibilityVersionMap.get(player);
        if (version == null || version != VoiceProxy.COMPATIBILITY_VERSION) {
            internalError(player, "Missing or incompatible client protocol version");
            throw new IncompatibleVoiceChatException("Matching TCP client required");
        }
        SniffedSecretPacket packet = SniffedSecretPacket.fromBytes(message, version);
        if (packet.getServerPort() != -1) {
            internalError(player, "Backend must enable proxy_mode=true");
            throw new IncompatibleVoiceChatException("Backend is not in central proxy mode");
        }
        UUID mapped = playerUUIDMap.get(packet.getPlayerUUID());
        if (mapped != null && !mapped.equals(player)) throw new IllegalArgumentException("Conflicting backend player UUID");
        String backend = voiceProxy.backendId(player);
        if (backend == null) throw new IllegalArgumentException("No current backend");
        voiceProxy.disconnectBridge(player);
        UUID old = backendPlayerMap.put(player, packet.getPlayerUUID());
        if (old != null) playerUUIDMap.remove(old, player);
        playerUUIDMap.put(packet.getPlayerUUID(), player);
        retiredIdentities.remove(packet.getPlayerUUID());
        backendIds.put(player, backend);
        secretMap.put(player, packet.getSecret());
        routes.remove(player);
        errors.remove(player);
        requestedAt.put(player, System.currentTimeMillis());
        return packet.patch(voiceProxy);
    }

    private void handleRouting(ByteBuffer data, UUID player) {
        UUID backendPlayer = readUUID(data);
        if (!player.equals(playerUUIDMap.get(backendPlayer)) || !backendPlayer.equals(backendPlayerMap.get(player))) {
            throw new IllegalArgumentException("Backend did not upload a matching player UUID mapping: " + backendPlayer);
        }
        if (!Objects.equals(backendIds.get(player), voiceProxy.backendId(player))) throw new IllegalArgumentException("Stale backend identity");
        long generation = data.getLong();
        long sequence = data.getLong();
        boolean snapshot = data.get() != 0;
        RoutingState previous = routes.get(player);
        if (previous != null) {
            if (generation != previous.generation()) throw new IllegalArgumentException("Backend generation changed without a new identity handshake");
            if (sequence <= previous.sequence()) return;
        }
        RoutingState next;
        long now = System.currentTimeMillis();
        if (!snapshot) {
            if (previous == null || data.hasRemaining()) throw new IllegalArgumentException("Heartbeat without routing snapshot");
            next = new RoutingState(backendPlayer, generation, sequence, now, previous.status(), previous.relay(),
                    previous.normalDistance(), previous.whisperDistance(), previous.normalTargets(), previous.whisperTargets(), previous.groupTargets());
        } else {
            int status = data.get() & 255;
            boolean relay = data.get() != 0;
            float normal = data.getFloat(), whisper = data.getFloat();
            if (status > VoiceAvailability.DISABLED || !Float.isFinite(normal) || !Float.isFinite(whisper) || normal < 0 || whisper < 0) throw new IllegalArgumentException("Invalid routing state");
            List<UUID> normals = readUUIDs(data), whispers = readUUIDs(data), groups = readUUIDs(data);
            if (data.hasRemaining()) throw new IllegalArgumentException("Trailing routing data");
            next = new RoutingState(backendPlayer, generation, sequence, now, status, relay, normal, whisper, normals, whispers, groups);
        }
        routes.put(player, next);
        voiceProxy.routingChanged(player);
        requestedAt.remove(player);
        errors.remove(player);
    }

    public synchronized void checkBackendTimeouts() {
        long now = System.currentTimeMillis();
        retiredIdentities.entrySet().removeIf(entry -> entry.getValue() < now);
        requestedAt.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < 5000L) return false;
            UUID player = entry.getKey();
            if (secretMap.containsKey(player)) internalError(player, "Backend did not upload usable UUID/routing metadata");
            else voiceProxy.availability(player, VoiceAvailability.UNAVAILABLE);
            return true;
        });
    }

    public synchronized void internalError(UUID player, String reason) {
        routes.remove(player);
        if (errors.add(player)) {
            voiceProxy.getLogger().error("Voice chat disabled for player {} on backend {}: {}", player, voiceProxy.backendId(player), reason);
            voiceProxy.availability(player, VoiceAvailability.INTERNAL_ERROR);
        }
        voiceProxy.disconnectBridge(player);
    }

    public synchronized void onPlayerServerDisconnect(UUID player) {
        UUID backendPlayer = backendPlayerMap.remove(player);
        if (backendPlayer != null) {
            playerUUIDMap.remove(backendPlayer, player);
            retiredIdentities.put(backendPlayer, System.currentTimeMillis() + 10_000L);
        }
        backendIds.remove(player);
        compatibilityVersionMap.remove(player);
        secretMap.remove(player);
        routes.remove(player);
        requestedAt.remove(player);
        errors.remove(player);
    }

    public byte[] getSecret(UUID player) {
        byte[] key = secretMap.get(player);
        return key == null ? null : key.clone();
    }
    public RoutingState getRoutingState(UUID player) {
        RoutingState state = routes.get(player);
        return state == null || System.currentTimeMillis() - state.updatedAt() > 3000L ? null : state;
    }
    public boolean sameBackend(UUID first, UUID second) {
        String backend = backendIds.get(first);
        return backend != null && backend.equals(backendIds.get(second));
    }
    public record RoutingState(UUID backendPlayer, long generation, long sequence, long updatedAt, int status, boolean relay,
                               float normalDistance, float whisperDistance, List<UUID> normalTargets, List<UUID> whisperTargets, List<UUID> groupTargets) {
        public boolean connected() { return status == VoiceAvailability.AVAILABLE; }
    }
    private static UUID readUUID(ByteBuffer b) { return new UUID(b.getLong(), b.getLong()); }
    private static List<UUID> readUUIDs(ByteBuffer b) {
        int count = 0, shift = 0;
        while (true) {
            if (shift >= 35) throw new IllegalArgumentException("Invalid routing count");
            int n = b.get() & 255; count |= (n & 127) << shift;
            if ((n & 128) == 0) break;
            shift += 7;
        }
        if (count < 0 || count > 4096 || count * 16L > b.remaining()) throw new IllegalArgumentException("Invalid routing targets");
        List<UUID> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(readUUID(b));
        return List.copyOf(result);
    }
}
