package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.VoiceProxy;
import de.maxhenkel.voicechat.voice.transport.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Exercises the production sniffer and request/reply codec with explicit game-platform boundaries. */
public class ProxyConfigSyncTest {
    private static int checks;
    static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks++;
    }
    static void invalid(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Malformed settings accepted");
    }
    public static void main(String[] args) throws Exception {
        VoiceProxy proxy = new VoiceProxy();
        proxy.getConfig().transportMode = new VoiceProxy.Value<>("dual");
        proxy.getConfig().transportPreference = new VoiceProxy.Value<>("udp,tcp");
        proxy.getConfig().voiceHost = new VoiceProxy.Value<>("voice.example.test:24454");
        UUID player = UUID.randomUUID();
        ProxyConfigSync sync = new ProxyConfigSync();
        UUID nonce = sync.begin(player, 1000L);
        check(nonce != null && sync.begin(player, 1001L) == null, "only one request per player can be pending");
        byte[] request = ProxyMessages.encode(ProxyMessages.CONFIG_REQUEST, 0L, nonce, new byte[0]);
        proxy.getSniffer().onPluginMessage(VoiceProxy.PROXY_CONTROL_CHANNEL, false, ByteBuffer.wrap(request), player);
        check(proxy.backend.isEmpty(), "client cannot forge a config request or obtain internal settings");
        proxy.getSniffer().onPluginMessage(VoiceProxy.PROXY_CONTROL_CHANNEL, true, ByteBuffer.wrap(request), player);
        var delivery = proxy.backend.remove();
        var response = ProxyMessages.decode(delivery.data());
        check(response.type() == ProxyMessages.CONFIG_RESPONSE && response.session().equals(nonce), "proxy echoes request nonce");
        ProxySettings settings = ProxySettings.decode(response.payload());
        check(settings.mode() == TransportMode.DUAL && settings.preference().equals("udp,tcp")
                && settings.port() == proxy.port && settings.voiceHost().equals("voice.example.test:24454"), "proxy owns all shared network fields");
        check(!sync.accept(player, UUID.randomUUID(), 1002L), "unsolicited config cannot complete request");
        check(sync.accept(player, nonce, 1002L) && !sync.accept(player, nonce, 1003L), "valid reply consumed exactly once");
        nonce = sync.begin(player, 2000L);
        check(!sync.accept(player, nonce, 7000L) && sync.expire(player, nonce), "late replies cannot authenticate voice");
        UUID old = sync.begin(player, 8000L); sync.remove(player);
        UUID current = sync.begin(player, 8001L);
        check(!sync.accept(player, old, 8002L) && !sync.expire(player, old), "retired session cannot affect a reconnect");
        check(sync.accept(player, current, 8002L), "fresh reconnect can synchronize");
        invalid(() -> ProxySettings.decode(new byte[0]));
        invalid(() -> ProxySettings.decode(Arrays.copyOf(settings.encode(), settings.encode().length + 1)));
        invalid(() -> new ProxySettings(TransportMode.DUAL, "tcp", 24454, ""));
        invalid(() -> new ProxySettings(TransportMode.DUAL, "tcp,udp", 0, ""));
        invalid(() -> new ProxySettings(TransportMode.DUAL, "tcp,udp", 24454, "bad\nvalue"));
        byte[] badSchema = settings.encode(); badSchema[3] = 99;
        invalid(() -> ProxySettings.decode(badSchema));
        // Published upstream 2.6.x clients send only a four-byte protocol 20 request.
        proxy.getSniffer().onPluginMessage(VoiceProxy.REQUEST_SECRET_CHANNEL, false, ByteBuffer.allocate(4).putInt(20).flip(), player);
        check(proxy.getSniffer().getProtocol(player) == VoiceProtocol.LEGACY_UDP, "unmodified upstream request selects UDP");
        check(proxy.client.isEmpty(), "original clients receive no fork-only availability messages");
        System.out.println("Proxy configuration sync: " + checks + " checks passed");
    }
}
