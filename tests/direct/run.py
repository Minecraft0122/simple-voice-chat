"""Compile actual common/Bukkit packet and session classes against small platform doubles.
Requires the Netty jars already resolved by Gradle; pass --netty-dir to override the cache.
"""
from pathlib import Path
import argparse, os, subprocess
parser=argparse.ArgumentParser()
parser.add_argument('--netty-dir',type=Path,default=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1/io.netty')
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
base='de.maxhenkel.voicechat'
jars=[]
for artifact in ('netty-buffer','netty-common','netty-codec'):
    versions=args.netty_dir/artifact
    matches=sorted(versions.glob('4.1.*/*/*.jar'), key=lambda p: tuple(int(n) for n in p.parents[1].name.split('.')[:3]))
    matches=[p for p in matches if not any(x in p.name for x in ('sources','javadoc'))]
    if not matches: raise SystemExit(f'Missing {artifact} 4.1 dependency; first run Gradle :bukkit:compileJava')
    jars.append(str(matches[-1]))
for module in ('common','bukkit'):
    out=root/f'build/direct-tests/{module}'
    stubs=out/'stubs';stubs.mkdir(parents=True,exist_ok=True);sources=[]
    def stub(name,body):
        package,_=name.rsplit('.',1);p=stubs/(name.replace('.','/')+'.java');p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text('package '+package+';\n'+body,encoding='utf-8');sources.append(str(p))
    def vc(name,body):stub(base+'.'+name,body)
    for annotation in ('Nonnull','Nullable'):stub('javax.annotation.'+annotation,'public @interface '+annotation+' {}')
    vc('Voicechat','public class Voicechat { public static final Logger LOGGER=new Logger(); public static class Logger { public void debug(String s,Object...args){} } }')
    vc('voice.common.Utils','public class Utils { public static final int MAX_VOICE_CHAT_PACKET_SIZE=2048; }')
    vc('voice.common.AudioUtils','public class AudioUtils { public static final int MAX_OPUS_PAYLOAD_SIZE=1275; public static byte[] shortsToBytes(short[] values){throw new UnsupportedOperationException("PCM path is outside these tests");} }')
    buf='net.minecraft.network.FriendlyByteBuf' if module=='common' else base+'.util.FriendlyByteBuf'
    vc('debug.PingHandler','public class PingHandler { public static boolean onPacket(de.maxhenkel.voicechat.voice.server.Server s,java.net.SocketAddress a,java.util.UUID u,'+buf+' b){return false;} }')
    # Only game-side lookup/routing is a double. Packet parsing, encryption and session key selection are real.
    vc('voice.server.Server','''import java.util.*;import java.net.*;import de.maxhenkel.voicechat.voice.common.*;import de.maxhenkel.voicechat.voice.transport.*;
public class Server {
 public final Map<UUID,Secret> secrets=new HashMap<>(); public final Map<UUID,VoiceProtocol> protocols=new HashMap<>(); public final Map<UUID,ClientConnection> sessions=new HashMap<>();
 public boolean hasSecret(UUID id){return secrets.containsKey(id);} public Secret getSecret(UUID id){return secrets.get(id);}
 public boolean acceptsTransport(UUID id,SocketAddress a){return protocols.containsKey(id)&&protocols.get(id).matches(a);}
 public ClientConnection findVoiceSession(UUID id,SocketAddress a){ClientConnection c=sessions.get(id);return c!=null&&c.getAddress().equals(a)?c:null;}
 public void sendConnectionMessage(ClientConnection c,NetworkMessage m){}
}''')
    if module=='common':
        # Use the production Bukkit byte buffer implementation as the Minecraft boundary adapter.
        text=(root/'bukkit/src/main/java/de/maxhenkel/voicechat/util/FriendlyByteBuf.java').read_text()
        p=stubs/'net/minecraft/network/FriendlyByteBuf.java';p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(text.replace('package de.maxhenkel.voicechat.util;','package net.minecraft.network;'),encoding='utf-8');sources.append(str(p))
        stub('net.minecraft.world.phys.Vec3','public class Vec3 { public final double x,y,z;public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;} }')
    else:
        sources.append(str(root/'bukkit/src/main/java/de/maxhenkel/voicechat/util/FriendlyByteBuf.java'))
        stub('org.bukkit.Location','public class Location { private final double x,y,z;public Location(Object w,double x,double y,double z){this.x=x;this.y=y;this.z=z;}public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;} }')
    production=root/f'{module}/src/main/java/de/maxhenkel/voicechat'
    names=['Packet','Secret','NetworkMessage','SoundPacket','MicPacket','PlayerSoundPacket','GroupSoundPacket','LocationSoundPacket','AuthenticatePacket','AuthenticateAckPacket','PingPacket','KeepAlivePacket','ConnectionCheckPacket','ConnectionCheckAckPacket','AuthenticationChallengePacket','AuthenticationResponsePacket','AvailabilityPacket']
    sources += [str(production/'voice/common'/f'{n}.java') for n in names]
    sources += [str(production/'voice/server/ClientConnection.java'),str(root/'api/src/main/java/de/maxhenkel/voicechat/api/RawUdpPacket.java')]
    sources += [str(p) for p in (root/'common/src/main/java/de/maxhenkel/voicechat/voice/transport').glob('*.java')]
    sources.append(str(root/'tests/direct/DirectProtocolTest.java'))
    cp=os.pathsep.join(jars)
    compiled = subprocess.run(['javac','--release','25','-encoding','UTF-8','-cp',cp,'-d',str(out),*sources])
    if compiled.returncode: raise SystemExit(compiled.returncode)
    print('Testing production '+module+' packets and sessions',flush=True)
    subprocess.run(['java','-ea','-cp',str(out)+os.pathsep+cp,base+'.voice.server.DirectProtocolTest'],check=True)
