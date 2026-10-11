package de.maxhenkel.voicechat.net;
import de.maxhenkel.voicechat.Voicechat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public class ProxyControlPacket implements Packet<ProxyControlPacket> {
    public static final CustomPacketPayload.Type<ProxyControlPacket> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Voicechat.MODID, "proxy_control"));
    private byte[] data;
    public ProxyControlPacket() {}
    public ProxyControlPacket(byte[] data) { this.data = data.clone(); }
    public byte[] getData() { return data.clone(); }
    @Override public ProxyControlPacket fromBytes(FriendlyByteBuf buf) {
        if (buf.readableBytes() > 4121) throw new IllegalArgumentException("Oversized proxy message");
        data = new byte[buf.readableBytes()]; buf.readBytes(data); return this;
    }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeBytes(data); }
    @Override public Type<ProxyControlPacket> type() { return TYPE; }

}
