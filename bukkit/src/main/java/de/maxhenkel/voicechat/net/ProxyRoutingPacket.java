package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ProxyRoutingPacket implements Packet<ProxyRoutingPacket> {

    public static final Key PROXY_ROUTING = Voicechat.compatibility.createNamespacedKey("proxy_routing");
    private static final int MAX_TARGETS = 4096;

    private UUID playerUUID;
    private long generation;
    private long updateSequence;
    private boolean connected;
    private float normalDistance;
    private float whisperDistance;
    private List<UUID> normalTargets = List.of();
    private List<UUID> whisperTargets = List.of();

    public ProxyRoutingPacket() {
    }

    public ProxyRoutingPacket(UUID playerUUID, long generation, long updateSequence, boolean connected, float normalDistance, float whisperDistance,
                              List<UUID> normalTargets, List<UUID> whisperTargets) {
        this.playerUUID = playerUUID;
        this.generation = generation;
        this.updateSequence = updateSequence;
        this.connected = connected;
        this.normalDistance = normalDistance;
        this.whisperDistance = whisperDistance;
        this.normalTargets = List.copyOf(normalTargets);
        this.whisperTargets = List.copyOf(whisperTargets);
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public boolean isConnected() {
        return connected;
    }

    public long getGeneration() {
        return generation;
    }

    public long getUpdateSequence() {
        return updateSequence;
    }

    public float getNormalDistance() {
        return normalDistance;
    }

    public float getWhisperDistance() {
        return whisperDistance;
    }

    public List<UUID> getNormalTargets() {
        return normalTargets;
    }

    public List<UUID> getWhisperTargets() {
        return whisperTargets;
    }

    @Override
    public Key getID() {
        return PROXY_ROUTING;
    }

    @Override
    public ProxyRoutingPacket fromBytes(FriendlyByteBuf buf) {
        playerUUID = buf.readUUID();
        generation = buf.readLong();
        updateSequence = buf.readLong();
        connected = buf.readBoolean();
        normalDistance = buf.readFloat();
        whisperDistance = buf.readFloat();
        normalTargets = readTargets(buf);
        whisperTargets = readTargets(buf);
        return this;
    }

    private static List<UUID> readTargets(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_TARGETS) throw new IllegalArgumentException("Invalid proxy routing target count");
        List<UUID> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(buf.readUUID());
        return List.copyOf(result);
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        if (normalTargets.size() > MAX_TARGETS || whisperTargets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("Too many proxy routing targets");
        }
        buf.writeUUID(playerUUID);
        buf.writeLong(generation);
        buf.writeLong(updateSequence);
        buf.writeBoolean(connected);
        buf.writeFloat(normalDistance);
        buf.writeFloat(whisperDistance);
        writeTargets(buf, normalTargets);
        writeTargets(buf, whisperTargets);
    }

    private static void writeTargets(FriendlyByteBuf buf, List<UUID> targets) {
        buf.writeVarInt(targets.size());
        for (UUID target : targets) buf.writeUUID(target);
    }
}
