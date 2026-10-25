package de.maxhenkel.voicechat.network;

import de.maxhenkel.voicechat.VoiceProxy;
import de.maxhenkel.voicechat.voice.transport.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import static de.maxhenkel.voicechat.network.ProxyRuntimeTest.*;

/** UDP fixtures independently serialize upstream protocol 20; the endpoint/crypto/router are production. */
public class MixedProxyTest {
    static int passed;
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    static void sound(byte[] packet, UUID sender, long sequence, byte value) {
        ByteBuffer b = ByteBuffer.wrap(packet); b.get();
        UUID channel = new UUID(b.getLong(), b.getLong());
        UUID entity = new UUID(b.getLong(), b.getLong());
        check(channel.equals(sender) && entity.equals(sender) && b.get() == 1 && b.get() == value && b.getLong() == sequence,
                "mixed audio preserves upstream identity, Opus bytes and sequence " + sequence);
    }
    static byte[] key() { byte[] key = new byte[16]; new SecureRandom().nextBytes(key); return key; }
    static void specialRoute(VoiceProxy p, UUID id, UUID entity, long sequence, List<UUID> whisper, List<UUID> group) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(256).putLong(entity.getMostSignificantBits()).putLong(entity.getLeastSignificantBits())
                .putLong(7).putLong(sequence).put((byte)1).put((byte)0).put((byte)0).putFloat(48).putFloat(16).put((byte)0);
        b.put((byte)whisper.size()); for(UUID target:whisper) b.putLong(target.getMostSignificantBits()).putLong(target.getLeastSignificantBits());
        b.put((byte)group.size()); for(UUID target:group) b.putLong(target.getMostSignificantBits()).putLong(target.getLeastSignificantBits());
        p.getSniffer().onPluginMessage(VoiceProxy.PROXY_ROUTING_CHANNEL,true,b.flip(),id);
    }
    static ProxyMessages.Message connection(VoiceProxy p, UUID id) throws Exception {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(System.nanoTime()<deadline) {
            for(VoiceProxy.Delivery d:p.backend) if(d.player().equals(id)) {
                ProxyMessages.Message m=ProxyMessages.decode(d.data());
                if(m.type()==ProxyMessages.CONNECTION) return m;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Missing backend connection");
    }
    public static void main(String[] args) throws Exception {
        VoiceProxy proxy = new VoiceProxy();
        proxy.getConfig().transportMode = new VoiceProxy.Value<>("dual");
        proxy.server = new VoiceProxyServer(proxy); proxy.server.start();
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID();
        UUID ea=UUID.randomUUID(), eb=UUID.randomUUID(), ec=UUID.randomUUID();
        byte[] ka=key(), kb=key(), kc=key();
        register(proxy,a,ea,ka); register(proxy,b,eb,kb,20); register(proxy,c,ec,kc,20);
        route(proxy,a,ea,1,0,false,List.of(eb));
        route(proxy,b,eb,1,0,false,List.of(ea,ec));
        route(proxy,c,ec,1,0,false,List.of(eb));
        try(Client tcp=new Client(proxy,ea,ka); UdpClient udp=new UdpClient(proxy,eb,kb); UdpClient udp2=new UdpClient(proxy,ec,kc)) {
            tcp.authenticate(); udp.authenticate(); udp2.authenticate();
            check(connection(proxy,b).payload()[0]==1, "upstream UDP authentication validates a backend session");
            check(proxy.client.stream().noneMatch(d->d.player().equals(b)||d.player().equals(c)), "legacy clients receive no custom Minecraft availability messages");
            tcp.mic(1,new byte[]{11}); sound(udp.readType(2),ea,1,(byte)11);
            udp.mic(100,(byte)12,false); sound(tcp.readType(2),eb,100,(byte)12); sound(udp2.readType(2),eb,100,(byte)12);
            udp.mic(98,(byte)13,false); sound(tcp.readType(2),eb,98,(byte)13); udp2.readType(2);
            udp.mic(0,(byte)14,false); sound(tcp.readType(2),eb,0,(byte)14); udp2.readType(2);
            check(true,"UDP reordering and microphone sequence reset retain upstream behavior");
            // Invalid traffic from the same source must not destroy an established UDP session.
            udp.raw(new byte[]{-1,0});
            udp.raw(udp.envelope(UUID.randomUUID(),new byte[]{8}));
            byte[] corrupt=udp.envelope(eb,new byte[]{8}); corrupt[corrupt.length-1]^=1; udp.raw(corrupt);
            udp.mic(1,(byte)15,false); sound(tcp.readType(2),eb,1,(byte)15); udp2.readType(2);
            udp.authenticate();
            tcp.mic(2,new byte[]{16}); sound(udp.readType(2),ea,2,(byte)16);
            // A captured valid UDP hello at another endpoint cannot evict a live endpoint.
            try(UdpClient replay=new UdpClient(proxy,eb,kb)) { replay.raw(udp.hello()); replay.expectSilence(); }
            tcp.mic(3,new byte[]{17}); sound(udp.readType(2),ea,3,(byte)17);
            // A TCP profile cannot be downgraded merely by using UDP with the master secret.
            try(UdpClient downgrade=new UdpClient(proxy,ea,ka)) { downgrade.raw(downgrade.hello()); downgrade.expectSilence(); }
            try(Client wrong=new Client(proxy,eb,kb)) {
                wrong.write(udp.hello()); check(wrong.input.read()==-1,"UDP profile cannot authenticate through TCP");
            }
            proxy.getSniffer().onPluginMessage(VoiceProxy.REQUEST_SECRET_CHANNEL,false,ByteBuffer.allocate(4).putInt(20).flip(),a);
            check(proxy.getSniffer().getProtocol(a)==VoiceProtocol.TCP,"repeated secret requests cannot switch a live protocol");
            // Group and whisper use the same recipients across both transports.
            route(proxy,a,ea,2,0,false,List.of(eb)); route(proxy,c,ec,2,0,false,List.of(eb));
            specialRoute(proxy,b,eb,2,List.of(ea),List.of(ec));
            udp.mic(2,(byte)18,true); byte[] whisper=tcp.readType(2); sound(whisper,eb,2,(byte)18);
            check(ByteBuffer.wrap(whisper,whisper.length-5,4).getFloat()==16F && whisper[whisper.length-1]==1,"cross-transport whisper keeps distance and whisper flag");
            sound(udp2.readType(3),eb,2,(byte)18);
            route(proxy,b,eb,3,VoiceAvailability.SPECTATOR,false,List.of(ea));
            udp.mic(3,(byte)19,false); udp.control(9); udp.readType(10);
            tcp.socket.setSoTimeout(200);
            try { tcp.readType(2); throw new AssertionError("spectator audio leaked"); } catch(SocketTimeoutException expected) { passed++; }
            tcp.socket.setSoTimeout(2500);
            route(proxy,b,eb,4,0,true,List.of());
            udp.mic(4,(byte)20,false); udp.control(9); udp.readType(10);
            check(proxy.backend.stream().anyMatch(d->d.player().equals(b)&&ProxyMessages.decode(d.data()).type()==ProxyMessages.MICROPHONE),"UDP microphones reach backend audio plugins");
            ProxyMessages.Message session=connection(proxy,b);
            byte[] apiAudio={3,55};
            proxy.relayAudio(b,ProxyMessages.encode(ProxyMessages.SOUND,7,session.session(),apiAudio));
            check(Arrays.equals(udp.readType(3),apiAudio),"backend API audio uses the UDP recipient's master key");
            proxy.getSniffer().onPlayerServerDisconnect(b);
            route(proxy,a,ea,3,0,false,List.of(eb));
            tcp.mic(4,new byte[]{21}); udp.expectSilence();
            check(proxy.getSniffer().getSecret(b)==null,"server switch clears UDP credentials and routing");
            byte[] replacement=key(); register(proxy,b,eb,replacement,20); route(proxy,b,eb,1,0,false,List.of(ea));
            udp.mic(5,(byte)22,false); udp.expectSilence();
            udp.secret=replacement; udp.authenticate(); udp.mic(0,(byte)23,false); sound(tcp.readType(2),eb,0,(byte)23);
            check(true,"fresh UDP authentication works after switch; old secret is rejected");
            // Advance just the idle timestamp; the production maintenance loop performs expiry.
            java.lang.reflect.Field sessionsField=VoiceProxyServer.class.getDeclaredField("playerConnections");
            sessionsField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<UUID,ProxyVoiceSession> sessions=(Map<UUID,ProxyVoiceSession>)sessionsField.get(proxy.server);
            ProxyVoiceSession expired=sessions.get(b); expired.lastReceived=System.nanoTime()-31_000_000_000L;
            long deadline=System.nanoTime()+2_000_000_000L;
            while(!expired.isClosed()&&System.nanoTime()<deadline)Thread.sleep(10);
            check(expired.isClosed(),"silent UDP peers expire without socket EOF");
            route(proxy,a,ea,4,0,false,List.of(eb)); route(proxy,b,eb,2,0,false,List.of(ea));
            try(UdpClient rebound=new UdpClient(proxy,eb,replacement)) {
                rebound.authenticate(); rebound.mic(0,(byte)24,false); sound(tcp.readType(2),eb,0,(byte)24);
                check(true,"UDP can reauthenticate at a different NAT endpoint after timeout");
            }
        } finally { proxy.server.interrupt(); proxy.server.join(2000); }
        // The default TCP mode explicitly rejects a protocol-20 secret handshake.
        VoiceProxy tcpOnly=new VoiceProxy();
        try { register(tcpOnly,b,eb,kb,20); throw new AssertionError("TCP-only accepted UDP"); }
        catch(de.maxhenkel.voicechat.sniffer.IncompatibleVoiceChatException expected) { passed++; }
        System.out.println("Mixed proxy: "+passed+" checks passed");
    }
    static class UdpClient implements AutoCloseable {
        final DatagramSocket socket; final UUID entity; byte[] secret;
        UdpClient(VoiceProxy proxy,UUID entity,byte[] secret) throws Exception {
            this.entity=entity; this.secret=secret;
            socket=new DatagramSocket(); socket.connect(InetAddress.getLoopbackAddress(),proxy.port); socket.setSoTimeout(1500);
        }
        // Encode directly from the upstream packet layout, independently of ProxyVoicePacketCodec.
        byte[] envelope(UUID id,byte[] plain) throws Exception {
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=cipher.doFinal(plain);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(255); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
            int size=iv.length+encrypted.length;
            while((size & ~127)!=0){out.writeByte((size&127)|128);size>>>=7;}out.writeByte(size);
            out.write(iv);out.write(encrypted);return bytes.toByteArray();
        }
        byte[] hello() throws Exception { return envelope(entity,ByteBuffer.allocate(33).put((byte)5).putLong(entity.getMostSignificantBits()).putLong(entity.getLeastSignificantBits()).put(secret).array()); }
        void raw(byte[] bytes) throws Exception { socket.send(new DatagramPacket(bytes,bytes.length)); }
        void control(int type) throws Exception { raw(envelope(entity,new byte[]{(byte)type})); }
        void authenticate() throws Exception { raw(hello()); readType(6); control(9); readType(10); }
        void mic(long sequence,byte audio,boolean whisper) throws Exception { raw(envelope(entity,ByteBuffer.allocate(12).put((byte)1).put((byte)1).put(audio).putLong(sequence).put((byte)(whisper?1:0)).array())); }
        byte[] readType(int expected) throws Exception {
            long deadline=System.nanoTime()+2_000_000_000L;
            while(System.nanoTime()<deadline) {
                byte[] data=new byte[4096]; DatagramPacket packet=new DatagramPacket(data,data.length);socket.receive(packet);
                DataInputStream in=new DataInputStream(new ByteArrayInputStream(data,0,packet.getLength()));
                if(in.readUnsignedByte()!=255)throw new AssertionError("invalid UDP envelope");
                int length=0,shift=0,n;do{n=in.readUnsignedByte();length|=(n&127)<<shift;shift+=7;}while((n&128)!=0);
                byte[] iv=in.readNBytes(12),encrypted=in.readNBytes(length-12);
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,iv));
                byte[] plain=cipher.doFinal(encrypted);int type=plain[0]&255;
                if(type>=11)throw new AssertionError("TCP extension sent to upstream UDP: "+type);
                if(type==expected)return plain;
                if(type!=8)throw new AssertionError("Unexpected UDP packet "+type+" while awaiting "+expected);
                control(8);
            }
            throw new AssertionError("No UDP response "+expected);
        }
        void expectSilence() throws Exception {
            socket.setSoTimeout(150);
            try { readType(2); throw new AssertionError("unexpected UDP audio"); } catch(SocketTimeoutException expected) { passed++; }
            finally { socket.setSoTimeout(1500); }
        }
        public void close(){socket.close();}
    }
}
