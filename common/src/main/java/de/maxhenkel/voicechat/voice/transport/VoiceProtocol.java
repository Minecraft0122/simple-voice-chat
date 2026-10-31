package de.maxhenkel.voicechat.voice.transport;

/** Wire profiles negotiated over the authenticated Minecraft connection. */
public enum VoiceProtocol {
    LEGACY_UDP(20, 1), TCP(1021, 2);

    private final int version;
    private final int capabilityBit;
    VoiceProtocol(int version, int capabilityBit) { this.version = version; this.capabilityBit = capabilityBit; }
    public int version() { return version; }
    public int capabilityBit() { return capabilityBit; }
    public boolean isTcp() { return this == TCP; }
    public static VoiceProtocol fromVersion(int version) {
        for (VoiceProtocol protocol : values()) if (protocol.version == version) return protocol;
        return null;
    }
    public boolean matches(java.net.SocketAddress address) {
        // Existing custom TCP sockets may still return a plain SocketAddress.
        return this == (address instanceof VoiceEndpoint endpoint ? endpoint.protocol() : TCP);
    }
}
