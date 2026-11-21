package de.maxhenkel.voicechat.voice.client;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.debug.VoicechatUncaughtExceptionHandler;
import de.maxhenkel.voicechat.intercompatibility.ClientCompatibilityManager;
import de.maxhenkel.voicechat.plugins.ClientPluginManager;
import de.maxhenkel.voicechat.voice.common.*;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class ClientVoicechatConnection extends Thread {

    private final ClientVoicechat client;
    private final InitializationData data;
    private final ClientVoicechatSocket socket;
    private final InetAddress address;
    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile boolean connected;
    private boolean disconnectEventEmitted;
    private final AuthThread authThread;
    private volatile long lastKeepAlive;
    private volatile de.maxhenkel.voicechat.voice.transport.VoiceHandshake handshake;
    private final java.util.concurrent.atomic.AtomicLong audioSequence = new java.util.concurrent.atomic.AtomicLong();

    public long nextAudioSequence() { return audioSequence.getAndIncrement(); }
    public boolean canSendAudio() { return isInitialized() && client.canSendAudio(); }
    public Secret getReadSecret() {
        return handshake == null ? data.getSecret() : Secret.fromBytes(handshake.serverKey());
    }
    public Secret getWriteSecret(Packet<?> packet) {
        if (packet instanceof AuthenticatePacket || packet instanceof AuthenticationResponsePacket || handshake == null) return data.getSecret();
        return Secret.fromBytes(handshake.clientKey());
    }

    public ClientVoicechatConnection(ClientVoicechat client, InitializationData data) throws Exception {
        this.client = client;
        this.data = data;
        this.socket = ClientPluginManager.instance().getClientSocketImplementation();
        this.socket.open();
        this.running = true;
        this.authThread = new AuthThread();
        setDaemon(true);
        setName("VoiceChatConnectionThread");
        setUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        this.socket.setTransport(data.getTransportProtocol());
        this.socket.open();
    }

    public InitializationData getData() {
        return data;
    }

    /**
     *
     * @return the voice chat servers address - May be <code>null</code> if DNS hasn't been resolved yet
     */
    @Nullable
    public InetAddress getAddress() {
        return address;
    }

    public ClientVoicechatSocket getSocket() {
        return socket;
    }

    public boolean isInitialized() {
        return authenticated && connected;
    }

    @Override
    public void run() {
        // TCP sockets are connected lazily by the first authentication packet.
        // Starting auth after this thread starts keeps connection ownership and
        // the packet reader in a single lifecycle.
        authThread.start();
        try {
            address = resolveAddress();
        } catch (UnknownHostException e) {
            if (running) {
                Voicechat.LOGGER.error("Failed to resolve voice chat server address '{}' - Disconnecting", data.getServerIP());
                disconnect();
            }
            return;
        }
        authThread.start();
        try {
            while (running) {
                RawUdpPacket rawPacket;
                try {
                    rawPacket = socket.read();
                } catch (ProtocolException e) {
                    if (Voicechat.debugMode()) {
                        Voicechat.LOGGER.warn("Failed to read packet", e);
                    } else {
                        CooldownTimer.run("socket_protocol_error", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet: {}", e.getMessage());
                        });
                    }
                    continue;
                }
                NetworkMessage in;
                try {
                    in = ClientNetworkMessage.readPacketClient(rawPacket, this);
                } catch (Exception e) {
                    if (Voicechat.debugMode()) {
                        Voicechat.LOGGER.debug("Failed to read packet from {}", rawPacket.getSocketAddress(), e);
                    } else {
                        CooldownTimer.run("failed_reading_packet", () -> {
                            Voicechat.LOGGER.warn("Failed to read packet from {}", rawPacket.getSocketAddress());
                        });
                    }
                    continue;
                }
                if (in == null) {
                    continue;
                } else if (in.getPacket() instanceof AuthenticationChallengePacket challenge) {
                    if (authenticated) continue;
                    handshake = new de.maxhenkel.voicechat.voice.transport.VoiceHandshake(data.getSecret().getSecret(), data.getPlayerUUID(), challenge.getData());
                    sendToServer(new NetworkMessage(new AuthenticationResponsePacket(handshake.proof())));
                } else if (in.getPacket() instanceof AvailabilityPacket availability) {
                    Minecraft.getInstance().execute(() -> {
                        if (client.getConnection() == this) client.setAvailability(availability.getStatus());
                    });
                } else if (in.getPacket() instanceof AuthenticateAckPacket) {
                    if (!authenticated) {
                        Voicechat.LOGGER.info("Server acknowledged authentication");
                        connectionCheckNonce = packet.getNonce();
                        authenticated = true;
                    }
                } else if (in.getPacket() instanceof ConnectionCheckAckPacket) {
                    if (authenticated && !connected) {
                        Voicechat.LOGGER.info("Server acknowledged connection check");
                        lastKeepAlive = Utils.getMilliseconds();
                        connected = true;
                        executeIfActive(() -> ClientCompatibilityManager.INSTANCE.emitVoiceChatConnectedEvent(this));
                    }
                } else if (in.getPacket() instanceof SoundPacket packet) {
                    client.processSoundPacket(packet);
                } else if (in.getPacket() instanceof PingPacket packet) {
                    Voicechat.LOGGER.info("Received ping {}, sending pong...", packet.getId());
                    sendToServer(new NetworkMessage(packet));
                } else if (in.getPacket() instanceof KeepAlivePacket) {
                    lastKeepAlive = Utils.getMilliseconds();
                    sendToServer(new NetworkMessage(new KeepAlivePacket()));
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            if (running) {
                failConnection(e);
            }
        }
    }

    private InetAddress resolveAddress() throws UnknownHostException {
        if (data.getServerIP().equals("rtc-local") || data.getServerIP().equals("rtc-remote")) {
            return InetAddress.getLoopbackAddress();
        }
        return InetAddress.getByName(data.getServerIP());
    }

    public void close() {
        disconnect(false);
    }

    private void disconnect(boolean emitEvent) {
        boolean closeSocket;
        boolean emit;
        synchronized (this) {
            closeSocket = running || !socket.isClosed();
            emit = emitEvent && !disconnectEventEmitted;
            running = false;
            authenticated = false;
            connected = false;
            disconnectEventEmitted = true;
        }
        if (closeSocket) {
            Voicechat.LOGGER.info("Disconnecting voicechat");
        }
        socket.close();
        authThread.close();
        if (emit && closeSocket) {
            Minecraft.getInstance().execute(() -> ClientCompatibilityManager.INSTANCE.emitVoiceChatDisconnectedEvent());
        }
    }

    public boolean isConnected() {
        return running && !socket.isClosed();
    }

    public boolean sendToServer(NetworkMessage message) {
        if (message.getPacket() instanceof MicPacket && !canSendAudio()) return false;
        if (!running || socket.isClosed()) {
            return false; // Ignore sending packets when connection is closed
        }
        try {
            SocketAddress destination = new InetSocketAddress(address, data.getServerPort());
            socket.send(ClientNetworkMessage.writeClient(this, message), destination);
            return true;
        } catch (Exception e) {
            Voicechat.LOGGER.error("Failed to send voice chat packet - Disconnecting", e);
            failConnection(e);
            return false;
        }
    }

    private void failConnection(Exception cause) {
        synchronized (this) {
            if (!running || disconnectEventEmitted) {
                return;
            }
        }
        Voicechat.LOGGER.error("Voice chat transport failed - Disconnecting", cause);
        disconnect(false);
        client.scheduleReconnect(this);
    }

    public void checkTimeout() {
        if (connected && Utils.getMilliseconds() - lastKeepAlive > data.getKeepAlive() * 10L) {
            Voicechat.LOGGER.info("Connection timeout");
            failConnection(new IOException("Voice chat keep-alive timed out"));
        }
    }

    public void disconnect() {
        disconnect(true);
    }

    private class AuthThread extends Thread {

        private volatile boolean running;
        private int authLogMessageCount;
        private int validateLogMessageCount;

        public AuthThread() {
            this.running = true;
            setDaemon(true);
            setName("VoiceChatAuthenticationThread");
            setDefaultUncaughtExceptionHandler(new VoicechatUncaughtExceptionHandler());
        }

        @Override
        public void run() {
            while (running) {
                if (authenticated && connected) {
                    break;
                }
                if (!authenticated) {
                    validateLogMessageCount = 0;
                    if (authLogMessageCount < 10) {
                        Voicechat.LOGGER.info("Trying to authenticate voice chat connection");
                        authLogMessageCount++;
                    } else if (authLogMessageCount == 10) {
                        Voicechat.LOGGER.warn("Trying to authenticate voice chat connection (this message will not be logged again)");
                        authLogMessageCount++;
                    }
                    if (!sendToServer(new NetworkMessage(handshake == null ? new AuthenticatePacket(data.getPlayerUUID(), data.getSecret()) : new AuthenticationResponsePacket(handshake.proof())))) {
                        break;
                    }
                } else {
                    authLogMessageCount = 0;
                    if (validateLogMessageCount < 10) {
                        Voicechat.LOGGER.info("Trying to validate voice chat connection");
                        validateLogMessageCount++;
                    } else if (validateLogMessageCount == 10) {
                        Voicechat.LOGGER.warn("Trying to validate voice chat connection (this message will not be logged again)");
                        validateLogMessageCount++;
                    }
                    if (!sendToServer(new NetworkMessage(new ConnectionCheckPacket()))) {
                        break;
                    }
                }

                Utils.sleep(1000);
            }
        }

        public void close() {
            running = false;
        }
    }

}
