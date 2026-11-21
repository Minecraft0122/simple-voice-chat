package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.util.Key;
import de.maxhenkel.voicechat.voice.common.AudioUtils;
import de.maxhenkel.voicechat.voice.common.Secret;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SecretPacket implements Packet<SecretPacket> {

    public static final Key SECRET = Voicechat.compatibility.createNamespacedKey("secret");

    private Secret secret;
    private UUID connectionId;
    private int serverPort;
    private UUID playerUUID;
    private ServerConfig.Codec codec;
    private int mtuSize;
    private double voiceChatDistance;
    private int keepAlive;
    private boolean groupsEnabled;
    private String voiceHost;
    private boolean allowRecording;
    private int transportProtocol = 1021;
    private boolean transportExtension = true;

    public SecretPacket withTransportExtension(boolean enabled) {
        transportExtension = enabled;
        return this;
    }

    public SecretPacket() {

    }

    public SecretPacket(Player player, Secret secret, int port, ServerConfig serverConfig, String voiceHost) {
        this(player, secret, port, serverConfig, voiceHost, 1021);
    }

    public SecretPacket(Player player, Secret secret, int port, ServerConfig serverConfig, String voiceHost, int transportProtocol) {
        this.secret = secret;
        this.connectionId = connectionId;
        this.serverPort = port;
        this.playerUUID = player.getUniqueId();
        this.codec = serverConfig.voiceChatCodec.get();
        this.mtuSize = serverConfig.voiceChatMtuSize.get();
        this.voiceChatDistance = serverConfig.voiceChatDistance.get();
        this.keepAlive = serverConfig.keepAlive.get();
        this.groupsEnabled = serverConfig.groupsEnabled.get();
        this.voiceHost = voiceHost;
        this.allowRecording = serverConfig.allowRecording.get();
        this.transportProtocol = transportProtocol;
    }

    public Secret getSecret() {
        return secret;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public int getServerPort() {
        return serverPort;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public ServerConfig.Codec getCodec() {
        return codec;
    }

    public int getMtuSize() {
        return mtuSize;
    }

    public double getVoiceChatDistance() {
        return voiceChatDistance;
    }

    public int getKeepAlive() {
        return keepAlive;
    }

    public boolean groupsEnabled() {
        return groupsEnabled;
    }

    public String getVoiceHost() {
        return voiceHost;
    }

    @Override
    public Key getID() {
        return SECRET;
    }

    public boolean allowRecording() {
        return allowRecording;
    }

    public int getTransportProtocol() { return transportProtocol; }

    @Override
    public SecretPacket fromBytes(FriendlyByteBuf buf) {
        secret = Secret.fromBytes(buf);
        connectionId = buf.readUUID();
        serverPort = buf.readInt();
        playerUUID = buf.readUUID();
        codec = ServerConfig.Codec.values()[buf.readByte()];
        mtuSize = Math.max(AudioUtils.MIN_MTU_SIZE, Math.min(buf.readInt(), AudioUtils.MAX_MTU_SIZE));
        voiceChatDistance = buf.readDouble();
        keepAlive = buf.readInt();
        groupsEnabled = buf.readBoolean();
        voiceHost = buf.readUtf();
        allowRecording = buf.readBoolean();
        if (buf.isReadable()) transportProtocol = buf.readInt();
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        secret.toBytes(buf);
        buf.writeUUID(connectionId);
        buf.writeInt(serverPort);
        buf.writeUUID(playerUUID);
        buf.writeByte(codec.ordinal());
        buf.writeInt(mtuSize);
        buf.writeDouble(voiceChatDistance);
        buf.writeInt(keepAlive);
        buf.writeBoolean(groupsEnabled);
        buf.writeUtf(voiceHost);
        buf.writeBoolean(allowRecording);
        // Legacy clients expect the packet to end after allowRecording.
        if (transportExtension) buf.writeInt(transportProtocol);
    }

}
