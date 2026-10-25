package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.voice.transport.TcpServer;
import de.maxhenkel.voicechat.voice.transport.DualVoiceServer;
import de.maxhenkel.voicechat.voice.transport.TransportMode;

import javax.annotation.Nullable;
import java.net.*;

/** Server-side socket for the configured TCP and/or UDP transports. */
public class VoicechatSocketImpl implements VoicechatSocket {

    @Nullable
    private volatile DualVoiceServer server;

    @Override
    public synchronized void open(int port, String bindAddress) throws Exception {
        if (server != null) {
            throw new IllegalStateException("Socket already opened");
        }
        boolean dedicated = CommonCompatibilityManager.INSTANCE.isDedicatedServer();
        if (dedicated) {
            checkCorrectHost();
        }
        String requestedAddress = bindAddress == null ? "" : bindAddress;
        try {
            if (!requestedAddress.isEmpty()) {
                InetAddress.getByName(requestedAddress);
            }
        } catch (UnknownHostException e) {
            Voicechat.LOGGER.error("Failed to parse bind IP address '{}'", requestedAddress, e);
            requestedAddress = "";
        }
        long timeout = Math.max(30_000L, Voicechat.SERVER_CONFIG.keepAlive.get() * 10L);
        DualVoiceServer transport = new DualVoiceServer(TransportMode.parse(Voicechat.SERVER_CONFIG.transportMode.get()), (int) Math.min(Integer.MAX_VALUE, timeout));
        try {
            transport.open(port, requestedAddress);
            server = transport;
        } catch (BindException e) {
            transport.close();
            Voicechat.LOGGER.error("Failed to run voice chat at voice port {}, make sure no other application is running at that port", port);
            Voicechat.LOGGER.error("Voice chat server error", e);
            if (dedicated) {
                Voicechat.LOGGER.error("Shutting down server");
                System.exit(1);
            }
            throw e;
        }
    }

    private void checkCorrectHost() throws Exception {
        String host = Voicechat.SERVER_CONFIG.voiceHost.get();
        if (host.isEmpty()) {
            return;
        }
        try {
            int port = Integer.parseInt(host);
            if (port <= 0 || port > 65535) {
                Voicechat.LOGGER.warn("Invalid voice host port: {}", port);
            } else {
                Voicechat.LOGGER.info("Voice host port is {}", port);
            }
        } catch (NumberFormatException ignored) {
            try {
                new URI("voicechat://" + host);
                Voicechat.LOGGER.info("Voice host is '{}'", host);
            } catch (URISyntaxException e) {
                Voicechat.LOGGER.warn("Failed to parse voice host", e);
                System.exit(1);
                throw e;
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        DualVoiceServer current = server;
        if (current == null) {
            throw new IllegalStateException("Socket not opened yet");
        }
        TcpServer.ReceivedPacket packet = current.read();
        return new RawUdpPacketImpl(packet.data(), packet.address(), packet.timestamp());
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        DualVoiceServer current = server;
        if (current == null || current.isClosed()) {
            return;
        }
        current.send(data, address);
    }

    @Override
    public void closeConnection(SocketAddress address) {
        DualVoiceServer current = server;
        if (current != null) {
            current.closeConnection(address);
        }
    }

    @Override
    public int getLocalPort() {
        DualVoiceServer current = server;
        return current == null ? -1 : current.getLocalPort();
    }

    @Override
    public synchronized void close() {
        DualVoiceServer current = server;
        server = null;
        if (current != null) {
            current.close();
        }
    }

    @Override
    public boolean isClosed() {
        DualVoiceServer current = server;
        return current == null || current.isClosed();
    }
}
