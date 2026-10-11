package de.maxhenkel.voicechat.voice.common;
import net.minecraft.network.FriendlyByteBuf;
public class AvailabilityPacket implements Packet<AvailabilityPacket> {
    private int status;
    public AvailabilityPacket() {}
    public AvailabilityPacket(int status) { this.status = status; }
    public int getStatus() { return status; }
    @Override public AvailabilityPacket fromBytes(FriendlyByteBuf buf) { status = buf.readUnsignedByte(); return this; }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeByte(status); }
}
