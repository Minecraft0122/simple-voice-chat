package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.voice.common.*;
import de.maxhenkel.voicechat.voice.transport.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Exercises both production NetworkMessage and ClientConnection variants with platform doubles. */
public class DirectProtocolTest {
    static int passed;
    static void check(boolean ok,String name) { if(!ok)throw new AssertionError(name); passed++;System.out.println("PASS "+name); }
    static byte[] client(UUID outer, Packet<?> packet, Secret secret) throws Exception {
        byte[] encrypted=new NetworkMessage(packet).write(secret);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(255);out.writeLong(outer.getMostSignificantBits());out.writeLong(outer.getLeastSignificantBits());
        int length=encrypted.length;while((length&~127)!=0){out.writeByte((length&127)|128);length>>>=7;}out.writeByte(length);out.write(encrypted);
        return bytes.toByteArray();
    }
    static NetworkMessage read(Server server, SocketAddress address, byte[] data) throws Exception {
        return NetworkMessage.readPacketServer(new RawUdpPacket(){
            public byte[] getData(){return data;} public SocketAddress getSocketAddress(){return address;}
            public long getTimestamp(){return System.currentTimeMillis();}
        },server);
    }
    static byte[] decryptServer(byte[] data,Secret secret) throws Exception {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));check(in.readUnsignedByte()==255,"server envelope magic");
        int length=0,shift=0,n;do{n=in.readUnsignedByte();length|=(n&127)<<shift;shift+=7;}while((n&128)!=0);
        return secret.decrypt(in.readNBytes(length));
    }
    public static void main(String[] args) throws Exception {
        Server server=new Server(); UUID udpId=UUID.randomUUID(),tcpId=UUID.randomUUID();
        Secret udpKey=Secret.generateNewRandomSecret(),tcpKey=Secret.generateNewRandomSecret();
        VoiceEndpoint udp=VoiceEndpoint.udp(new InetSocketAddress("127.0.0.1",4567));
        VoiceEndpoint tcp=VoiceEndpoint.tcp(new InetSocketAddress("127.0.0.1",4567));
        server.secrets.put(udpId,udpKey);server.secrets.put(tcpId,tcpKey);
        server.protocols.put(udpId,VoiceProtocol.LEGACY_UDP);server.protocols.put(tcpId,VoiceProtocol.TCP);
        NetworkMessage hello=read(server,udp,client(udpId,new AuthenticatePacket(udpId,udpKey),udpKey));
        check(hello!=null&&hello.getPacket() instanceof AuthenticatePacket&&udpId.equals(hello.getSenderUUID()),"upstream hello retains authenticated envelope UUID");
        check(read(server,tcp,client(udpId,new AuthenticatePacket(udpId,udpKey),udpKey))==null,"UDP identity rejected on TCP endpoint");
        check(read(server,udp,client(tcpId,new AuthenticatePacket(tcpId,tcpKey),tcpKey))==null,"TCP identity rejected on UDP endpoint");
        check(read(server,udp,client(udpId,new AuthenticatePacket(tcpId,udpKey),udpKey))==null,"inner and outer authentication UUID must agree");
        check(read(server,udp,client(udpId,new MicPacket(new byte[]{1},false,0),udpKey))==null,"unauthenticated UDP microphone is rejected");
        check(read(server,udp,client(udpId,new AuthenticationResponsePacket(new byte[32]),udpKey))==null,"UDP cannot use TCP proof packet");
        ClientConnection us=new ClientConnection(udpId,udp);server.sessions.put(udpId,us);
        check(us.isLegacy()&&us.getReadSecret(server).equals(udpKey)&&us.getWriteSecret(server,new AuthenticateAckPacket()).equals(udpKey),"UDP session keeps upstream shared secret in both directions");
        NetworkMessage checkPacket=read(server,udp,client(udpId,new ConnectionCheckPacket(),udpKey));
        check(checkPacket!=null&&checkPacket.getPacket() instanceof ConnectionCheckPacket,"upstream connection validation decodes after authentication");
        for(long seq:new long[]{100,98,0}) {
            NetworkMessage mic=read(server,udp,client(udpId,new MicPacket(new byte[]{42},true,seq),udpKey));
            check(mic!=null&&mic.getPacket() instanceof MicPacket m&&m.getSequenceNumber()==seq&&m.isWhispering(),"upstream microphone layout accepts sequence "+seq);
        }
        byte[] ack=decryptServer(new NetworkMessage(new AuthenticateAckPacket()).writeServer(server,us),udpKey);
        check(Arrays.equals(ack,new byte[]{6}),"UDP acknowledgement has original plaintext packet 0x06");
        ClientConnection ts=new ClientConnection(tcpId,tcp);ts.beginAuthentication(tcpKey.getSecret());
        VoiceHandshake peer=new VoiceHandshake(tcpKey.getSecret(),tcpId,ts.getHandshake().challenge());
        check(ts.authenticate(peer.proof()),"TCP challenge authentication retained");server.sessions.put(tcpId,ts);
        NetworkMessage mic=read(server,tcp,client(tcpId,new MicPacket(new byte[]{44},false,5),Secret.fromBytes(peer.clientKey())));
        check(mic!=null&&mic.getPacket() instanceof MicPacket,"TCP client directional key decrypts microphone");
        check(read(server,tcp,client(tcpId,new MicPacket(new byte[]{44},false,6),tcpKey))==null,"TCP master secret cannot encrypt authenticated audio");
        Packet<?> sound=new PlayerSoundPacket(udpId,udpId,new byte[]{7,8},9,true,16F,null);
        byte[] tcpSound=decryptServer(new NetworkMessage(sound).writeServer(server,ts),Secret.fromBytes(peer.serverKey()));
        byte[] udpSound=decryptServer(new NetworkMessage(sound).writeServer(server,us),udpKey);
        check(Arrays.equals(tcpSound,udpSound),"both receivers get identical sound payload under their own key profile");
        byte[] corrupt=client(udpId,new KeepAlivePacket(),udpKey);corrupt[corrupt.length-1]^=1;
        check(read(server,udp,corrupt)==null,"tampered UDP ciphertext is discarded");
        server.secrets.remove(udpId);server.sessions.remove(udpId);
        check(read(server,udp,client(udpId,new KeepAlivePacket(),udpKey))==null,"logged-out UDP secret no longer accepts traffic");
        System.out.println("Direct protocol: "+passed+" checks passed");
    }
}
