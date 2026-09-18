package de.maxhenkel.voicechat.plugins.impl;

import de.maxhenkel.voicechat.api.ClientVoicechatSocket;
import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.voice.transport.TcpConnection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

public class ClientVoicechatSocketImpl implements ClientVoicechatSocket {

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private final Object stateLock = new Object();
    private volatile TcpConnection connection;
    private Socket connectingSocket;
    private boolean opened;
    private volatile boolean closed = true;
    private boolean connecting;

    @Override
    public void open() throws Exception {
        synchronized (stateLock) {
            if (opened) {
                throw new IllegalStateException("Socket already opened");
            }
            opened = true;
            closed = false;
            connection = null;
        }
    }

    @Override
    public RawUdpPacket read() throws Exception {
        TcpConnection current;
        synchronized (stateLock) {
            while (opened && !closed && connection == null) {
                try {
                    stateLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
            if (!opened || closed) {
                throw new IOException("Socket is closed");
            }
            current = connection;
        }
        try {
            byte[] data = current.read();
            return new RawUdpPacketImpl(data, current.getRemoteAddress(), System.currentTimeMillis());
        } catch (IOException e) {
            throw e;
        }
    }

    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        if (data == null) {
            throw new NullPointerException("data");
        }
        try {
            getOrConnect(address).send(data);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private TcpConnection getOrConnect(SocketAddress address) throws IOException, InterruptedException {
        if (!(address instanceof InetSocketAddress inetAddress)) {
            throw new IOException("TCP voice chat requires an internet socket address");
        }
        Socket rawSocket;
        synchronized (stateLock) {
            while (connecting && !closed && connection == null) {
                stateLock.wait();
            }
            if (!opened || closed) {
                throw new IOException("Socket is closed");
            }
            if (connection != null) {
                if (!connection.getRemoteAddress().equals(address)) {
                    throw new IOException("TCP voice chat socket is already connected to a different server");
                }
                return connection;
            }
            rawSocket = new Socket();
            connectingSocket = rawSocket;
            connecting = true;
        }
        TcpConnection connected = null;
        try {
            rawSocket.connect(inetAddress, CONNECT_TIMEOUT_MILLIS);
            connected = new TcpConnection(rawSocket);
            synchronized (stateLock) {
                if (!opened || closed) {
                    connected.close();
                    throw new IOException("Socket was closed while connecting");
                }
                connection = connected;
                stateLock.notifyAll();
                return connected;
            }
        } catch (IOException | RuntimeException e) {
            if (connected != null) {
                connected.close();
            } else {
                try {
                    rawSocket.close();
                } catch (IOException ignored) {
                }
            }
            throw e;
        } finally {
            synchronized (stateLock) {
                connectingSocket = null;
                connecting = false;
                stateLock.notifyAll();
            }
        }
    }

    @Override
    public void close() {
        TcpConnection current;
        Socket pending;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            current = connection;
            pending = connectingSocket;
            stateLock.notifyAll();
        }
        if (pending != null) {
            try {
                pending.close();
            } catch (IOException ignored) {
            }
        }
        if (current != null) {
            current.close();
        }
    }

    @Override
    public boolean isClosed() {
        TcpConnection current = connection;
        return closed || (current != null && current.isClosed());
    }
}
