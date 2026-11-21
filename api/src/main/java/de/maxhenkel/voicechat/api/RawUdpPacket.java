package de.maxhenkel.voicechat.api;

import java.net.SocketAddress;

/**
 * One complete voice packet and its transport metadata.
 * The historical API name is retained; the TCP fork receives these packets as length-prefixed TCP frames.
 */
public interface RawUdpPacket {

    byte[] getData();

    /**
     * This is currently unused by the mod, but it should still provide a correct value.
     *
     * @return the timestamp the packet has been received
     */
    long getTimestamp();

    SocketAddress getSocketAddress();

}
