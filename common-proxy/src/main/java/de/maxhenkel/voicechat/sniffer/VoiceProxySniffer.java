package de.maxhenkel.voicechat.sniffer;

import de.maxhenkel.voicechat.VoiceProxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VoiceProxySniffer implements a platform-independent way of sniffing
 * the Simple Voice Chat packets as they are transmitted through plugin channels.
 */
public class VoiceProxySniffer {

    /**
     * Maps the backend server player UUID to the proxy player UUID.
     * This is useful when UUID forwarding has not been properly configured.
     */
    private final Map<UUID, UUID> playerUUIDMap = new ConcurrentHashMap<>();

    /**
     * Maps a given player UUID to the address and sniffed port of the backend voice chat server.
     */
    private final Map<UUID, InetSocketAddress> backendSocketMap = new ConcurrentHashMap<>();

    /**
     * Maps a given player UUID to the sniffed compatibility version.
     */
    private final Map<UUID, Integer> compatibilityVersionMap = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> secretMap = new ConcurrentHashMap<>();
    private final Map<UUID, RoutingState> routingStateMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> routingGenerationMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> routingSequenceMap = new ConcurrentHashMap<>();

    private final VoiceProxy voiceProxy;

    public VoiceProxySniffer(VoiceProxy voiceProxy) {
        this.voiceProxy = voiceProxy;
    }

    /**
     * Returns the players UUID on the proxy server.
     *
     * @param playerUUID the UUID of the player on the backend server
     * @return the UUID of the player on the proxy
     */
    public UUID getMappedPlayerUUID(UUID playerUUID) {
        return playerUUIDMap.getOrDefault(playerUUID, playerUUID);
    }

    /**
     * Returns the address and sniffed port of the backend voice chat server.
     *
     * @param playerUUID the UUID of the player on the proxy
     * @return the backend voice chat socket address, which may be unresolved, <code>null</code> if the secret handshake was not sniffed yet
     */
    public InetSocketAddress getBackendSocket(UUID playerUUID) {
        return backendSocketMap.get(playerUUID);
    }

    /**
     * Forgets the backend voice chat socket of a given player.
     * No packets are bridged for this player until the backend server times them out and sends a new secret.
     *
     * @param playerUUID the UUID of the player on the proxy
     */
    public void resetBackendSocket(UUID playerUUID) {
        backendSocketMap.remove(playerUUID);
    }

    /**
     * Called whenever a PluginMessage has been received by the proxy.
     *
     * @param channel    the channel on which the message was received
     * @param fromServer whether the message was sent from the server
     * @param message    the contents of the received message
     * @param playerUUID the UUID of the player that sent or received the message
     * @return ByteBuffer if the plugin message should be replaced, <code>null</code> otherwise
     */
    public ByteBuffer onPluginMessage(String channel, boolean fromServer, ByteBuffer message, UUID playerUUID) throws IncompatibleVoiceChatException {
        if (!fromServer && (channel.equals(VoiceProxy.REQUEST_SECRET_CHANNEL) || channel.equals(VoiceProxy.REQUEST_SECRET_CHANNEL_1_12))) {
            return handleRequestSecretPacket(message, playerUUID);
        }
        if (fromServer && (channel.equals(VoiceProxy.SECRET_CHANNEL) || channel.equals(VoiceProxy.SECRET_CHANNEL_1_12))) {
            return handleSecretPacket(message, playerUUID);
        }
        if (fromServer && channel.equals(VoiceProxy.PROXY_ROUTING_CHANNEL)) {
            try {
                handleRoutingPacket(message, playerUUID);
            } catch (RuntimeException e) {
                throw new IncompatibleVoiceChatException("Malformed proxy routing packet", e);
            }
            return ByteBuffer.allocate(0);
        }
        return null;
    }

    /**
     * Called whenever a Player disconnects from a backend server.
     *
     * @param playerUUID the UUID of the player that disconnected
     */
    public void onPlayerServerDisconnect(UUID playerUUID) {
        backendSocketMap.remove(playerUUID);
        compatibilityVersionMap.remove(playerUUID);
        // Remove by the proxies known player UUID e.g., the value of the map
        playerUUIDMap.values().remove(playerUUID);
        secretMap.remove(playerUUID);
        routingStateMap.remove(playerUUID);
        routingGenerationMap.remove(playerUUID);
        routingSequenceMap.remove(playerUUID);
    }

    /**
     * Called whenever a SecretPacket has been sniffed.
     *
     * @param message    the SecretPacket in bytes
     * @param playerUUID the UUID of the player this packet was intended for
     */
    private ByteBuffer handleSecretPacket(ByteBuffer message, UUID playerUUID) throws IncompatibleVoiceChatException {
        Integer compatibilityVersion = compatibilityVersionMap.get(playerUUID);
        if (compatibilityVersion == null) {
            throw new IncompatibleVoiceChatException("No compatibility version found");
        }
        SniffedSecretPacket packet = SniffedSecretPacket.fromBytes(message, compatibilityVersion);
        playerUUIDMap.put(packet.getPlayerUUID(), playerUUID);
        secretMap.put(playerUUID, packet.getSecret());

        InetSocketAddress backendSocket = createBackendSocket(playerUUID, packet.getServerPort());
        if (backendSocket == null) {
            resetBackendSocket(playerUUID);
        } else {
            backendSocketMap.put(playerUUID, backendSocket);
        }

        // The player reconnects with a new socket, so the bridge of the previous session is outdated
        voiceProxy.disconnectBridge(playerUUID);
        return packet.patch(voiceProxy);
    }

    private void handleRoutingPacket(ByteBuffer message, UUID proxyPlayerUUID) {
        ByteBuffer data = message.slice();
        UUID backendPlayerUUID = readUUID(data);
        UUID mappedProxyPlayerUUID = playerUUIDMap.get(backendPlayerUUID);
        if (mappedProxyPlayerUUID != null && !mappedProxyPlayerUUID.equals(proxyPlayerUUID)) {
            throw new IllegalArgumentException("Proxy routing player UUID does not match the plugin message player");
        }
        long generation = data.getLong();
        long updateSequence = data.getLong();
        UUID mappedPlayer = mappedProxyPlayerUUID == null ? proxyPlayerUUID : mappedProxyPlayerUUID;
        Long previousGeneration = routingGenerationMap.get(mappedPlayer);
        Long previousSequence = routingSequenceMap.get(mappedPlayer);
        if (previousGeneration != null && previousGeneration == generation && previousSequence != null && updateSequence <= previousSequence) {
            return;
        }
        if (previousGeneration != null && previousGeneration != generation) {
            routingSequenceMap.remove(mappedPlayer);
        }
        boolean connected = data.get() != 0;
        float normalDistance = data.getFloat();
        float whisperDistance = data.getFloat();
        List<UUID> normalTargets = readUUIDs(data);
        List<UUID> whisperTargets = readUUIDs(data);
        routingGenerationMap.put(mappedPlayer, generation);
        routingSequenceMap.put(mappedPlayer, updateSequence);
        routingStateMap.put(mappedPlayer, new RoutingState(mappedPlayer, connected, normalDistance, whisperDistance, normalTargets, whisperTargets));
    }

    private static UUID readUUID(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static List<UUID> readUUIDs(ByteBuffer buffer) {
        int count = readVarInt(buffer);
        if (count < 0 || count > 4096 || buffer.remaining() < count * 16L) {
            throw new IllegalArgumentException("Invalid proxy routing target count: " + count);
        }
        java.util.ArrayList<UUID> result = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(readUUID(buffer));
        }
        return List.copyOf(result);
    }

    private static int readVarInt(ByteBuffer buffer) {
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            int value = buffer.get() & 0xFF;
            result |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IllegalArgumentException("Proxy routing varint is too long");
    }

    public byte[] getSecret(UUID playerUUID) {
        byte[] secret = secretMap.get(playerUUID);
        return secret == null ? null : secret.clone();
    }

    public RoutingState getRoutingState(UUID playerUUID) {
        RoutingState state = routingStateMap.get(playerUUID);
        if (state == null) return null;
        return state.mapTargets(this);
    }

    public record RoutingState(UUID playerUUID, boolean connected, float normalDistance, float whisperDistance,
                               List<UUID> normalTargets, List<UUID> whisperTargets) {
        private RoutingState mapTargets(VoiceProxySniffer sniffer) {
            return new RoutingState(playerUUID, connected, normalDistance, whisperDistance,
                    map(normalTargets, sniffer), map(whisperTargets, sniffer));
        }

        private static List<UUID> map(List<UUID> targets, VoiceProxySniffer sniffer) {
            return targets.stream().map(target -> sniffer.playerUUIDMap.getOrDefault(target, target)).toList();
        }
    }

    /**
     * Creates the address of the backend voice chat server, which may be unresolved.
     * Unresolved addresses are resolved by the bridge itself to keep the name resolution off the thread that proxies the voice chat packets.
     *
     * @param playerUUID the UUID of the player on the proxy
     * @param serverPort the sniffed UDP port of the backend voice chat server
     * @return the backend voice chat socket or <code>null</code> if the player is not connected to a backend server
     */
    private InetSocketAddress createBackendSocket(UUID playerUUID, int serverPort) {
        InetSocketAddress backendSocket = voiceProxy.getDefaultBackendSocket(playerUUID);
        if (backendSocket == null) {
            return null;
        }

        InetAddress backendAddress = backendSocket.getAddress();
        if (backendAddress == null) {
            return InetSocketAddress.createUnresolved(backendSocket.getHostString(), serverPort);
        }
        return new InetSocketAddress(backendAddress, serverPort);
    }

    /**
     * Called whenever a RequestSecretPacket has been sniffed.
     *
     * @param message    the RequestSecretPacket in bytes
     * @param playerUUID the UUID of the player this packet was from
     */
    private ByteBuffer handleRequestSecretPacket(ByteBuffer message, UUID playerUUID) {
        compatibilityVersionMap.put(playerUUID, message.getInt());
        return null;
    }

}
