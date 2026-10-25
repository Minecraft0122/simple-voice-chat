package de.maxhenkel.voicechat.voice.transport;

/** Wire profiles negotiated over the authenticated Minecraft connection. */
public enum VoiceProtocol {
    LEGACY_UDP(20), TCP(1021);

    private final int version;
    VoiceProtocol(int version) { this.version = version; }
    public int version() { return version; }
    public static VoiceProtocol fromVersion(int version) {
        for (VoiceProtocol protocol : values()) if (protocol.version == version) return protocol;
        return null;
    }
    public boolean matches(java.net.SocketAddress address) {
        // Existing custom TCP sockets may still return a plain SocketAddress.
        return this == (address instanceof VoiceEndpoint endpoint ? endpoint.protocol() : TCP);
    }
}
