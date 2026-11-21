package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import de.maxhenkel.voicechat.voice.transport.VoiceProtocol;

public class RequestSecretPacket implements Packet<RequestSecretPacket> {

    public static final CustomPacketPayload.Type<RequestSecretPacket> REQUEST_SECRET = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Voicechat.MODID, "request_secret"));

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

    public RequestSecretPacket withTransportExtension(boolean enabled) {
        transportExtension = enabled;
        return this;
    }

    public int getTransportCapabilities() { return transportCapabilities == 0 ? capability(compatibilityVersion) : transportCapabilities; }
    public String getTransportPreference() { return transportPreference; }

    private static int capability(int version) {
        return version == VoiceProtocol.TCP.version() ? VoiceProtocol.TCP.capabilityBit()
                : version >= 18 ? VoiceProtocol.LEGACY_UDP.capabilityBit() : 0;
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
            transportPreference = compatibilityVersion == VoiceProtocol.TCP.version() ? "tcp" : "udp";
        }
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeInt(compatibilityVersion);
        if (transportExtension) {
            buf.writeByte(transportCapabilities == 0 ? capability(compatibilityVersion) : transportCapabilities);
            buf.writeUtf(transportPreference, 32);
        }
    }

    @Override
    public Type<RequestSecretPacket> type() {
        return REQUEST_SECRET;
    }

}
