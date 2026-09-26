package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.VoiceProxy;
import de.maxhenkel.voicechat.voice.transport.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Real socket regression tests for identity, authentication, routing and backend control. */
public class ProxyRuntimeTest {
    private static int passed;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) throws Exception {
        VoiceProxy proxy = new VoiceProxy();
        proxy.server = new VoiceProxyServer(proxy);
        proxy.server.start();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID entityA = UUID.randomUUID(), entityB = UUID.randomUUID();
        byte[] keyA = new byte[16], keyB = new byte[16];
        new java.security.SecureRandom().nextBytes(keyA);
        new java.security.SecureRandom().nextBytes(keyB);
        register(proxy, a, entityA, keyA); register(proxy, b, entityB, keyB);
        route(proxy, a, entityA, 1, 0, false, List.of(entityB));
        route(proxy, b, entityB, 1, 0, false, List.of(entityA));
        try (Client first = new Client(proxy, entityA, keyA); Client second = new Client(proxy, entityB, keyB)) {
            first.authenticate(); second.authenticate();
            check(proxy.backend.stream().anyMatch(d -> d.player().equals(a)), "backend receives authenticated connection status");
            first.mic(100, new byte[]{42});
            byte[] sound = second.readType(2);
            ByteBuffer packet = ByteBuffer.wrap(sound); packet.get();
            UUID channel = new UUID(packet.getLong(), packet.getLong());
            UUID sender = new UUID(packet.getLong(), packet.getLong());
            check(channel.equals(entityA) && sender.equals(entityA), "routing preserves backend entity UUID when proxy UUID differs");

            // Captured authentication cannot replace a live player's socket.
            try (Client attacker = new Client(proxy, entityA, keyA)) {
                attacker.write(first.hello);
                attacker.readMasterType(0x0B);
                attacker.write(first.proofFrame);
                boolean closed;
                try { attacker.input.readInt(); closed = false; } catch (EOFException e) { closed = true; }
                check(closed, "authentication proof replay is rejected on a new TCP connection");
            }
            first.mic(101, new byte[]{43});
            check(second.readType(2)[0] == 2, "failed replay leaves original connection usable");
            first.mic(101, new byte[]{44});
            first.mic(102, new byte[]{45});
            byte[] afterReplay = second.readType(2);
            check(afterReplay[afterReplay.length - 1 - 4 - 8 - 1] == 45, "duplicate microphone sequence is not forwarded");

            // Heartbeats refresh existing routes, without transmitting full target lists.
            ByteBuffer heartbeat = ByteBuffer.allocate(33).putLong(entityA.getMostSignificantBits()).putLong(entityA.getLeastSignificantBits())
                    .putLong(7).putLong(2).put((byte) 0);
            proxy.getSniffer().onPluginMessage(VoiceProxy.PROXY_ROUTING_CHANNEL, true, heartbeat.flip(), a);
            check(proxy.getSniffer().getRoutingState(a).normalTargets().equals(List.of(entityB)), "short heartbeat preserves recipient snapshot");

            route(proxy, a, entityA, 3, VoiceAvailability.SPECTATOR, false, List.of());
            first.mic(103, new byte[]{46});
            check(first.readType(0x0D)[1] == VoiceAvailability.SPECTATOR, "spectator receives explicit stop-sending status");
            route(proxy, a, entityA, 4, VoiceAvailability.AVAILABLE, true, List.of());
            first.mic(104, new byte[]{47});
            VoiceProxy.Delivery delivery = awaitMicrophone(proxy, a);
            ProxyMessages.Message microphone = ProxyMessages.decode(delivery.data());
            check(microphone.payload()[0] == 1, "packet-dependent addons receive microphone data through trusted control channel");
            ProxyMessages.Message targetStatus = proxy.backend.stream().filter(d -> d.player().equals(b))
                    .map(d -> ProxyMessages.decode(d.data())).filter(m -> m.type() == ProxyMessages.CONNECTION).findFirst().orElseThrow();
            byte[] apiAudio = new byte[]{3, 55};
            proxy.relayAudio(b, ProxyMessages.encode(ProxyMessages.SOUND, 7, targetStatus.session(), apiAudio));
            check(Arrays.equals(second.readType(3), apiAudio), "backend API audio is re-encrypted for the recipient session");

            UUID pingId = UUID.randomUUID();
            byte[] ping = ByteBuffer.allocate(25).put((byte) 7).putLong(pingId.getMostSignificantBits())
                    .putLong(pingId.getLeastSignificantBits()).putLong(1234).array();
            proxy.relayAudio(b, ProxyMessages.encode(ProxyMessages.SOUND, 7, targetStatus.session(), ping));
            byte[] pong = second.readType(7);
            second.write(ProxyVoicePacketCodec.encodeClientPacket(second.handshake.clientKey(), entityB, pong));
            VoiceProxy.Delivery returned = awaitControl(proxy, b, ProxyMessages.PONG);
            check(Arrays.equals(ProxyMessages.decode(returned.data()).payload(), ping), "API ping response returns to the originating backend session");

            int errorsBeforeLogout = proxy.errors.size();
            proxy.getSniffer().onPlayerServerDisconnect(b);
            route(proxy, a, entityA, 5, VoiceAvailability.AVAILABLE, false, List.of(entityB));
            first.mic(105, new byte[]{48});
            // A later control acknowledgement proves the preceding frame has been processed.
            first.write(ProxyVoicePacketCodec.encodeClientPacket(first.handshake.clientKey(), entityA, new byte[]{9}));
            first.readType(0x0A);
            check(proxy.errors.size() == errorsBeforeLogout && proxy.getSniffer().getRoutingState(a) != null,
                    "departed recipient in a recent snapshot does not disable the sender");
            Thread.sleep(3050);
            check(proxy.getSniffer().getRoutingState(a) == null, "expired backend state cannot authorize voice routing");
            byte[] availability;
            do { availability = first.readType(0x0D); } while (availability[1] == VoiceAvailability.AVAILABLE);
            check(availability[1] == VoiceAvailability.WAITING, "stale backend metadata tells the client to stop audio");

            int before = proxy.errors.size();
            UUID unknown = UUID.randomUUID();
            try { route(proxy, a, unknown, 6, 0, false, List.of()); } catch (Exception expected) {}
            check(proxy.errors.size() > before && proxy.client.stream().anyMatch(d -> d.player().equals(a) && d.data()[0] == VoiceAvailability.INTERNAL_ERROR),
                    "missing uploaded UUID fails closed with client error and console diagnostic");
            ByteBuffer ignored = proxy.getSniffer().onPluginMessage(VoiceProxy.PROXY_CONTROL_CHANNEL, false, ByteBuffer.wrap(new byte[]{1}), b);
            check(ignored != null && ignored.remaining() == 0, "client cannot forge proxy control messages");
        } finally { proxy.server.interrupt(); proxy.server.join(2000); }

        VoiceHandshake one = VoiceHandshake.create(keyA, a);
        VoiceHandshake peer = new VoiceHandshake(keyA, a, one.challenge());
        check(one.verify(peer.proof()) && Arrays.equals(one.clientKey(), peer.clientKey()), "challenge proof and HKDF keys agree");
        check(!Arrays.equals(one.clientKey(), one.serverKey()), "client and server traffic keys are separate");
        check(!VoiceHandshake.create(keyA, a).verify(peer.proof()), "proof is bound to a fresh server challenge");
        check(!new VoiceHandshake(keyA, b, one.challenge()).verify(peer.proof()), "proof is bound to player identity");
        UUID unsupported = UUID.randomUUID();
        proxy.getSniffer().onPluginMessage(VoiceProxy.REQUEST_SECRET_CHANNEL, false, ByteBuffer.allocate(4).putInt(1021).flip(), unsupported);
        Thread.sleep(5100);
        proxy.getSniffer().checkBackendTimeouts();
        check(proxy.client.stream().anyMatch(d -> d.player().equals(unsupported) && d.data()[0] == VoiceAvailability.UNAVAILABLE), "backend without plugin produces unavailable status");
        System.out.println("Proxy runtime: " + passed + " checks passed");
    }

    private static VoiceProxy.Delivery awaitMicrophone(VoiceProxy proxy, UUID player) throws Exception {
        return awaitControl(proxy, player, ProxyMessages.MICROPHONE);
    }
    private static VoiceProxy.Delivery awaitControl(VoiceProxy proxy, UUID player, int type) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            for (VoiceProxy.Delivery d : proxy.backend) {
                if (d.player().equals(player) && ProxyMessages.decode(d.data()).type() == type) return d;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No backend control message");
    }
    private static void register(VoiceProxy proxy, UUID player, UUID entity, byte[] secret) throws Exception {
        proxy.getSniffer().onPluginMessage(VoiceProxy.REQUEST_SECRET_CHANNEL, false, ByteBuffer.allocate(4).putInt(1021).flip(), player);
        ByteBuffer data = ByteBuffer.allocate(64).put(secret).putInt(-1).putLong(entity.getMostSignificantBits()).putLong(entity.getLeastSignificantBits())
                .put((byte) 0).putInt(1024).putDouble(48).putInt(1000).put((byte) 1).put((byte) 0).put((byte) 0);
        proxy.getSniffer().onPluginMessage(VoiceProxy.SECRET_CHANNEL, true, data.flip(), player);
    }
    private static void route(VoiceProxy proxy, UUID player, UUID entity, long sequence, int status, boolean relay, List<UUID> targets) throws Exception {
        ByteBuffer data = ByteBuffer.allocate(128 + targets.size() * 16).putLong(entity.getMostSignificantBits()).putLong(entity.getLeastSignificantBits())
                .putLong(7).putLong(sequence).put((byte) 1).put((byte) status).put((byte) (relay ? 1 : 0)).putFloat(48).putFloat(16).put((byte) targets.size());
        for (UUID target : targets) data.putLong(target.getMostSignificantBits()).putLong(target.getLeastSignificantBits());
        data.put((byte) 0).put((byte) 0);
        proxy.getSniffer().onPluginMessage(VoiceProxy.PROXY_ROUTING_CHANNEL, true, data.flip(), player);
    }
    private static class Client implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final UUID player;
        final byte[] secret;
        byte[] hello, proofFrame;
        VoiceHandshake handshake;
        Client(VoiceProxy proxy, UUID player, byte[] secret) throws Exception {
            Socket connected = null;
            for (int i = 0; i < 100 && connected == null; i++) {
                try { connected = new Socket("127.0.0.1", proxy.port); } catch (ConnectException e) { Thread.sleep(10); }
            }
            if (connected == null) throw new IOException("Proxy did not start");
            socket = connected; socket.setSoTimeout(2500); socket.setTcpNoDelay(true);
            input = new DataInputStream(socket.getInputStream()); output = new DataOutputStream(socket.getOutputStream());
            this.player = player; this.secret = secret;
        }
        void authenticate() throws Exception {
            hello = ProxyVoicePacketCodec.encodeClientPacket(secret, player, ByteBuffer.allocate(33).put((byte) 5)
                    .putLong(player.getMostSignificantBits()).putLong(player.getLeastSignificantBits()).put(secret).array());
            write(hello);
            byte[] challenge = readMasterType(0x0B);
            handshake = new VoiceHandshake(secret, player, Arrays.copyOfRange(challenge, 1, 33));
            proofFrame = ProxyVoicePacketCodec.encodeClientPacket(secret, player, ByteBuffer.allocate(33).put((byte) 0x0C).put(handshake.proof()).array());
            write(proofFrame); readType(6);
            write(ProxyVoicePacketCodec.encodeClientPacket(handshake.clientKey(), player, new byte[]{9}));
            readType(0x0A); readType(0x0D);
        }
        void mic(long sequence, byte[] audio) throws Exception {
            ByteBuffer payload = ByteBuffer.allocate(11 + audio.length).put((byte) 1).put((byte) audio.length).put(audio).putLong(sequence).put((byte) 0);
            write(ProxyVoicePacketCodec.encodeClientPacket(handshake.clientKey(), player, payload.array()));
        }
        void write(byte[] bytes) throws Exception { output.writeInt(bytes.length); output.write(bytes); output.flush(); }
        byte[] readMasterType(int type) throws Exception { return readType(type, secret); }
        byte[] readType(int type) throws Exception { return readType(type, handshake.serverKey()); }
        byte[] readType(int type, byte[] key) throws Exception {
            for (int i = 0; i < 20; i++) {
                byte[] frame = input.readNBytes(input.readInt());
                ByteBuffer buffer = ByteBuffer.wrap(frame); buffer.get();
                int length = 0, shift = 0, n;
                do { n = buffer.get() & 255; length |= (n & 127) << shift; shift += 7; } while ((n & 128) != 0);
                byte[] iv = new byte[12]; buffer.get(iv); byte[] ciphertext = new byte[length - 12]; buffer.get(ciphertext);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                byte[] plaintext = cipher.doFinal(ciphertext);
                if ((plaintext[0] & 255) == type) return plaintext;
            }
            throw new AssertionError("Packet type not received: " + type);
        }
        public void close() throws Exception { socket.close(); }
    }
}
