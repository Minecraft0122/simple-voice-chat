package de.maxhenkel.voicechat;

import de.maxhenkel.voicechat.logging.*;
import de.maxhenkel.voicechat.network.VoiceProxyServer;
import de.maxhenkel.voicechat.sniffer.VoiceProxySniffer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Only the Minecraft platform adapter is replaced. The endpoint, sniffer and crypto are production code. */
public class VoiceProxy {
    public static final int COMPATIBILITY_VERSION = 1021;
    public static final String REQUEST_SECRET_CHANNEL = "voicechat:request_secret", REQUEST_SECRET_CHANNEL_1_12 = "vc:request_secret",
            SECRET_CHANNEL = "voicechat:secret", SECRET_CHANNEL_1_12 = "vc:secret", PROXY_ROUTING_CHANNEL = "voicechat:proxy_routing",
            PROXY_CONTROL_CHANNEL = "voicechat:proxy_control", PROXY_AUDIO_CHANNEL = "voicechat:proxy_audio", AVAILABILITY_CHANNEL = "voicechat:availability";
    public static boolean isPrivateChannel(String s) { return Set.of(PROXY_ROUTING_CHANNEL, PROXY_CONTROL_CHANNEL, PROXY_AUDIO_CHANNEL, AVAILABILITY_CHANNEL).contains(s); }
    public record Value<T>(T value) { public T get() { return value; } }
    public static class Config {
        public Value<String> bindAddress = new Value<>("127.0.0.1"), voiceHost = new Value<>("");
        public Value<Boolean> allowPings = new Value<>(true);
    }
    public record Delivery(UUID player, String channel, byte[] data) {}
    public final Queue<Delivery> backend = new ConcurrentLinkedQueue<>(), client = new ConcurrentLinkedQueue<>();
    public final Queue<String> errors = new ConcurrentLinkedQueue<>();
    private final Config config = new Config();
    private final VoiceProxySniffer sniffer = new VoiceProxySniffer(this);
    public VoiceProxyServer server;
    public final int port;
    public VoiceProxy() throws Exception { try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); } }
    public int getPort() { return port; }
    public Config getConfig() { return config; }
    public InetSocketAddress getDefaultBindSocket() { return new InetSocketAddress("127.0.0.1", port); }
    public InetSocketAddress getDefaultBackendSocket(UUID p) { return new InetSocketAddress("127.0.0.1", 25565); }
    public String backendId(UUID p) { return "test-backend"; }
    public VoiceProxySniffer getSniffer() { return sniffer; }
    public void disconnectBridge(UUID p) { if (server != null) server.disconnect(p); }
    public void availability(UUID p, int state) { sendToPlayer(p, AVAILABILITY_CHANNEL, new byte[]{(byte) state}); }
    public void sendToPlayer(UUID p, String channel, byte[] bytes) { client.add(new Delivery(p, channel, bytes)); }
    public void sendToBackend(UUID p, String channel, byte[] bytes) { backend.add(new Delivery(p, channel, bytes)); }
    public void routingChanged(UUID p) { if (server != null) server.routingChanged(p); }
    public void relayAudio(UUID p, byte[] bytes) { server.relayBackendAudio(p, bytes); }
    public VoiceChatLogger getLogger() { return new VoiceChatLogger() {
        public boolean isEnabled(LogLevel level) { return true; }
        public void log(LogLevel level, String message, Object... args) { if (level == LogLevel.ERROR) errors.add(message); }
    }; }
}
