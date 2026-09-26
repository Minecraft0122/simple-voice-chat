package de.maxhenkel.voicechat.net;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;
public class VoiceAvailabilityPacket implements Packet<VoiceAvailabilityPacket> {
    public static final Key TYPE = Voicechat.compatibility.createNamespacedKey("availability");
    private int status;
    public VoiceAvailabilityPacket() {}
    public VoiceAvailabilityPacket(int status) { this.status = status; }
    public int getStatus() { return status; }
    @Override public VoiceAvailabilityPacket fromBytes(FriendlyByteBuf buf) { status = buf.readUnsignedByte(); return this; }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeByte(status); }
    @Override public Key getID() { return TYPE; }
}
