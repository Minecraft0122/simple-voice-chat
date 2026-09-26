package de.maxhenkel.voicechat.net;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;
import org.bukkit.entity.Player;
public class ProxyAudioPacket implements Packet<ProxyAudioPacket> {
    public static final Key TYPE = Voicechat.compatibility.createNamespacedKey("proxy_audio");
    private byte[] data;
    public ProxyAudioPacket() {}
    public ProxyAudioPacket(byte[] data) { this.data = data.clone(); }
    public byte[] getData() { return data.clone(); }
    @Override public ProxyAudioPacket fromBytes(FriendlyByteBuf buf) {
        if (buf.readableBytes() > 4121) throw new IllegalArgumentException("Oversized proxy message");
        data = new byte[buf.readableBytes()]; buf.readBytes(data); return this;
    }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeBytes(data); }
    @Override public Key getID() { return TYPE; }

}
