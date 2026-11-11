package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;
import de.maxhenkel.voicechat.voice.transport.VoiceVersion;
import org.bukkit.entity.Player;

/** Only reply after the client has explicitly advertised support for this channel. */
public class VersionPacket implements Packet<VersionPacket> {
    public static final Key VERSION = Voicechat.compatibility.createNamespacedKey("version");
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
    @Override public Key getID() { return VERSION; }
    @Override public void onPacket(Player player) { Voicechat.SERVER.onVersionPacket(player, this); }
}
