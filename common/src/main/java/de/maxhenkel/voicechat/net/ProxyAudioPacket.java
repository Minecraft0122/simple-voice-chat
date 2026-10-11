package de.maxhenkel.voicechat.net;
import de.maxhenkel.voicechat.Voicechat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public class ProxyAudioPacket implements Packet<ProxyAudioPacket> {
    public static final CustomPacketPayload.Type<ProxyAudioPacket> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Voicechat.MODID, "proxy_audio"));
    private byte[] data;
    public ProxyAudioPacket() {}
    public ProxyAudioPacket(byte[] data) { this.data = data.clone(); }
    public byte[] getData() { return data.clone(); }
    @Override public ProxyAudioPacket fromBytes(FriendlyByteBuf buf) {
        if (buf.readableBytes() > 4121) throw new IllegalArgumentException("Oversized proxy message");
        data = new byte[buf.readableBytes()]; buf.readBytes(data); return this;
    }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeBytes(data); }
    @Override public Type<ProxyAudioPacket> type() { return TYPE; }

}
