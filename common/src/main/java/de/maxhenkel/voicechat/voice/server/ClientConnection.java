package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.voice.common.NetworkMessage;

import java.net.SocketAddress;
import java.util.UUID;

public class ClientConnection {

    private de.maxhenkel.voicechat.voice.transport.VoiceHandshake handshake;
    private boolean authenticated;
    private UUID proxySession;
    public void setProxySession(UUID session) { proxySession = session; }
    public UUID getProxySession() { return proxySession; }
    public void beginAuthentication(byte[] secret) { handshake = de.maxhenkel.voicechat.voice.transport.VoiceHandshake.create(secret, playerUUID); }
    public de.maxhenkel.voicechat.voice.transport.VoiceHandshake getHandshake() { return handshake; }
    public boolean authenticate(byte[] proof) { authenticated = handshake != null && handshake.verify(proof); return authenticated; }
    public de.maxhenkel.voicechat.voice.common.Secret getReadSecret(Server server) {
        return authenticated ? de.maxhenkel.voicechat.voice.common.Secret.fromBytes(handshake.clientKey()) : server.getSecret(playerUUID);
    }
    public de.maxhenkel.voicechat.voice.common.Secret getWriteSecret(Server server, de.maxhenkel.voicechat.voice.common.Packet<?> packet) {
        return authenticated && !(packet instanceof de.maxhenkel.voicechat.voice.common.AuthenticationChallengePacket)
                ? de.maxhenkel.voicechat.voice.common.Secret.fromBytes(handshake.serverKey()) : server.getSecret(playerUUID);
    }
    protected final UUID playerUUID;
    protected final SocketAddress address;
    protected long lastKeepAliveResponse;

    public ClientConnection(UUID playerUUID, SocketAddress address) {
        this.playerUUID = playerUUID;
        this.address = address;
        this.lastKeepAliveResponse = System.currentTimeMillis();
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public SocketAddress getAddress() {
        return address;
    }

    public long getLastKeepAliveResponse() {
        return lastKeepAliveResponse;
    }

    public void setLastKeepAliveResponse(long lastKeepAliveResponse) {
        this.lastKeepAliveResponse = lastKeepAliveResponse;
    }

    public void send(Server server, NetworkMessage message) throws Exception {
        server.sendConnectionMessage(this, message);
    }

}
