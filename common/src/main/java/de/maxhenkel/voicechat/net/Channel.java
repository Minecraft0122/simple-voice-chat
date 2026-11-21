package de.maxhenkel.voicechat.net;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

public class Channel<T extends Packet<T>> {

    @Nullable
    private NetManager.ServerReceiver<T> serverListener;

    public Channel() {

    }

    public void setServerListener(NetManager.ServerReceiver<T> packetReceiver) {
        serverListener = packetReceiver;
    }

    public void onServerPacket(ServerPlayer player, T packet) {
        if (Voicechat.SERVER.getServer() == null) return;
        CommonCompatibilityManager.INSTANCE.executeForPlayer(Voicechat.SERVER.getServer().getServer(), player, () -> {
            if (!(packet instanceof ProxyControlPacket && Voicechat.SERVER_CONFIG.isProxyMode()) && !Voicechat.SERVER.getRateLimiter().allow(player.getUUID())) {
                Voicechat.LOGGER.warn("Player {} exceeded packet rate limit", player.getName().getString());
                player.connection.disconnect(Component.translatableWithFallback("disconnect.exceeded_packet_rate", "Kicked for exceeding packet rate limit"));
                return;
            }
            if (!player.connection.isAcceptingMessages()) return;
            if (serverListener != null) serverListener.onPacket(player, packet);
        }, () -> {});
    }

}
