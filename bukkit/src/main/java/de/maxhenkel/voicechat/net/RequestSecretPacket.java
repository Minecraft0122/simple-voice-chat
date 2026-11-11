package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;
import de.maxhenkel.voicechat.voice.transport.VoiceProtocol;
import org.bukkit.entity.Player;

public class RequestSecretPacket implements Packet<RequestSecretPacket> {

    public static final Key REQUEST_SECRET = Voicechat.compatibility.createNamespacedKey("request_secret");

    private int compatibilityVersion;
    private int transportCapabilities;
    private boolean transportExtension;
    private String transportPreference = "tcp,udp";

    public RequestSecretPacket() {

    }

    public RequestSecretPacket(int compatibilityVersion) {
        this.transportExtension = true;
        this.compatibilityVersion = compatibilityVersion;
        this.transportCapabilities = capability(compatibilityVersion);
    }

    public RequestSecretPacket(int compatibilityVersion, int transportCapabilities, String transportPreference) {
        this.transportExtension = true;
        this.compatibilityVersion = compatibilityVersion;
        this.transportCapabilities = transportCapabilities;
        this.transportPreference = transportPreference == null ? "tcp,udp" : transportPreference;
    }

    public int getCompatibilityVersion() {
        return compatibilityVersion;
    }

    public boolean hasTransportExtension() { return transportExtension; }

    public int getTransportCapabilities() { return transportCapabilities == 0 ? capability(compatibilityVersion) : transportCapabilities; }
    public String getTransportPreference() { return transportPreference; }

    private static int capability(int version) {
        VoiceProtocol protocol = VoiceProtocol.fromVersion(version);
        return protocol == null ? 0 : protocol.capabilityBit();
    }

    @Override
    public Key getID() {
        return REQUEST_SECRET;
    }

    @Override
    public void onPacket(Player player) {
        Voicechat.SERVER.onRequestSecretPacket(player, this);
    }

    @Override
    public RequestSecretPacket fromBytes(FriendlyByteBuf buf) {
        compatibilityVersion = buf.readInt();
        transportExtension = buf.isReadable();
        if (transportExtension) {
            transportCapabilities = buf.readUnsignedByte();
            transportPreference = buf.readUtf(32);
        } else {
            transportCapabilities = capability(compatibilityVersion);
            transportPreference = compatibilityVersion == 20 ? "udp" : "tcp";
        }
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(compatibilityVersion);
        buf.writeByte(transportCapabilities == 0 ? capability(compatibilityVersion) : transportCapabilities);
        buf.writeUtf(transportPreference, 32);
    }

}
