package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;
import de.maxhenkel.voicechat.voice.transport.TcpServer;
import org.bukkit.Bukkit;

import java.net.BindException;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

public class VoicechatSocketImpl implements VoicechatSocket {

    private volatile TcpServer socket;

    @Override
    public void open(int port, String bindAddress) throws Exception {
        if (socket != null) {
            throw new IllegalStateException("Socket already opened");
        }
        checkCorrectHost();
        String requestedAddress = bindAddress == null ? "" : bindAddress;
        try {
            if (!requestedAddress.isEmpty()) {
                InetAddress.getByName(requestedAddress);
            }
        } catch (Exception e) {
            requestedAddress = "";
            Voicechat.LOGGER.error("Failed to parse bind IP address '{}'", bindAddress, e);
        }

        TcpServer transport = new TcpServer(Math.max(30_000, Voicechat.SERVER_CONFIG.keepAlive.get() * 10));
        try {
            try {
                transport.open(port, requestedAddress);
            } catch (BindException e) {
                if (requestedAddress.isEmpty() || requestedAddress.equals("0.0.0.0")) {
                    throw e;
                }
                Voicechat.LOGGER.error("Failed to bind to address '{}', binding to wildcard IP instead", requestedAddress);
                transport.open(port, "");
            }
            socket = transport;
        } catch (BindException e) {
            transport.close();
            Voicechat.LOGGER.fatal("Failed to run voice chat at TCP port {}, make sure no other application is running at that port", port);
            Bukkit.getScheduler().runTask(Voicechat.INSTANCE, () -> {
                Voicechat.LOGGER.fatal("Disabling {}", BuildConstants.PLUGIN_NAME);
                Bukkit.getPluginManager().disablePlugin(Voicechat.INSTANCE);
            });
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
                Bukkit.shutdown();
                throw e;
            }
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        TcpServer current = socket;
        if (current == null) {
            throw new IllegalStateException("Socket not opened yet");
        }
        TcpServer.ReceivedPacket packet = current.read();
        return new RawUdpPacketImpl(packet.data(), packet.address(), packet.timestamp());
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        TcpServer current = socket;
        if (current == null || current.isClosed()) {
            return; // Ignoring packet sending when socket isn't open yet or already closed
        }
        current.send(data, address);
    }

    @Override
    public void closeConnection(SocketAddress address) {
        TcpServer current = socket;
        if (current != null) {
            current.closeConnection(address);
        }
    }

    @Override
    public int getLocalPort() {
        TcpServer current = socket;
        return current == null ? -1 : current.getLocalPort();
    }

    @Override
    public void close() {
        TcpServer current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
    }

    @Override
    public boolean isClosed() {
        TcpServer current = socket;
        return current == null || current.isClosed();
    }
}
