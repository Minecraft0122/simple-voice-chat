package de.maxhenkel.voicechat.voice.transport;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.UUID;

/** Separates UDP peers from TCP peers and prevents reuse of a retired TCP connection. */
public final class VoiceEndpoint extends SocketAddress {
    private final VoiceProtocol protocol;
    private final SocketAddress remote;
    private final UUID connection;

    private VoiceEndpoint(VoiceProtocol protocol, SocketAddress remote, UUID connection) {
        this.protocol = protocol;
        this.remote = Objects.requireNonNull(remote);
        this.connection = connection;
    }
    public static VoiceEndpoint tcp(SocketAddress remote) { return new VoiceEndpoint(VoiceProtocol.TCP, remote, UUID.randomUUID()); }
    public static VoiceEndpoint udp(SocketAddress remote) { return new VoiceEndpoint(VoiceProtocol.LEGACY_UDP, remote, null); }
    public VoiceProtocol protocol() { return protocol; }
    public SocketAddress remote() { return remote; }
    @Override public boolean equals(Object other) {
        return other instanceof VoiceEndpoint e && protocol == e.protocol && remote.equals(e.remote) && Objects.equals(connection, e.connection);
    }
    @Override public int hashCode() { return Objects.hash(protocol, remote, connection); }
    @Override public String toString() { return protocol + ":" + remote + (connection == null ? "" : "/" + connection); }
}
