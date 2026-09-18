package de.maxhenkel.voicechat.api;

import java.net.SocketAddress;

/**
 * One complete voice packet and its transport metadata.
 * The historical API name is retained; the TCP fork receives these packets as length-prefixed TCP frames.
 */
public interface RawUdpPacket {

    byte[] getData();

    long getTimestamp();

    SocketAddress getSocketAddress();

}
