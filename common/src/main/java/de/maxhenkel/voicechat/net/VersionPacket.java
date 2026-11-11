package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.voice.transport.VoiceVersion;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Separate channel: adding release strings to existing packets breaks older decoders. */
public class VersionPacket implements Packet<VersionPacket> {
    public static final CustomPacketPayload.Type<VersionPacket> VERSION = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Voicechat.MODID, "version"));
    private String version = "unknown";
    private int protocol;

    public VersionPacket() {}
    public VersionPacket(String version, int protocol) {
        this.version = VoiceVersion.sanitize(version);
        this.protocol = protocol;
    }
    public String getVersion() { return version; }
    public int getProtocol() { return protocol; }
    @Override public VersionPacket fromBytes(FriendlyByteBuf buf) {
        version = VoiceVersion.sanitize(buf.readUtf(128));
        protocol = buf.readInt();
        return this;
    }
    @Override public void toBytes(FriendlyByteBuf buf) {
        buf.writeUtf(version, 128);
        buf.writeInt(protocol);
    }
    @Override public Type<VersionPacket> type() { return VERSION; }
}
