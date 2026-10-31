package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.voice.common.Secret;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class SecretPacket implements Packet<SecretPacket> {

    public static final CustomPacketPayload.Type<SecretPacket> SECRET = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Voicechat.MODID, "secret"));

    private Secret secret;
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

    public SecretPacket() {

    }

    public SecretPacket(ServerPlayer player, Secret secret, int port, ServerConfig serverConfig, String voiceHost) {
        this(player, secret, port, serverConfig, voiceHost, 1021);
    }

    public SecretPacket(ServerPlayer player, Secret secret, int port, ServerConfig serverConfig, String voiceHost, int transportProtocol) {
        this.secret = secret;
        this.serverPort = port;
        this.playerUUID = player.getUUID();
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

    public boolean allowRecording() {
        return allowRecording;
    }

    public int getTransportProtocol() { return transportProtocol; }

    @Override
    public SecretPacket fromBytes(FriendlyByteBuf buf) {
        secret = Secret.fromBytes(buf);
        serverPort = buf.readInt();
        playerUUID = buf.readUUID();
        codec = ServerConfig.Codec.values()[buf.readByte()];
        mtuSize = buf.readInt();
        voiceChatDistance = buf.readDouble();
        keepAlive = buf.readInt();
        groupsEnabled = buf.readBoolean();
        voiceHost = buf.readUtf(32767);
        allowRecording = buf.readBoolean();
        if (buf.isReadable()) transportProtocol = buf.readInt();
        return this;
    }

    @Override
    public void toBytes(FriendlyByteBuf buf) {
        secret.toBytes(buf);
        buf.writeInt(serverPort);
        buf.writeUUID(playerUUID);
        buf.writeByte(codec.ordinal());
        buf.writeInt(mtuSize);
        buf.writeDouble(voiceChatDistance);
        buf.writeInt(keepAlive);
        buf.writeBoolean(groupsEnabled);
        buf.writeUtf(voiceHost);
        buf.writeBoolean(allowRecording);
        buf.writeInt(transportProtocol);
    }

    @Override
    public Type<SecretPacket> type() {
        return SECRET;
    }

}

