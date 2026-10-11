package de.maxhenkel.voicechat.net;
import de.maxhenkel.voicechat.Voicechat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public class VoiceAvailabilityPacket implements Packet<VoiceAvailabilityPacket> {
    public static final CustomPacketPayload.Type<VoiceAvailabilityPacket> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Voicechat.MODID, "availability"));
    private int status;
    public VoiceAvailabilityPacket() {}
    public VoiceAvailabilityPacket(int status) { this.status = status; }
    public int getStatus() { return status; }
    @Override public VoiceAvailabilityPacket fromBytes(FriendlyByteBuf buf) { status = buf.readUnsignedByte(); return this; }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeByte(status); }
    @Override public Type<VoiceAvailabilityPacket> type() { return TYPE; }
}
