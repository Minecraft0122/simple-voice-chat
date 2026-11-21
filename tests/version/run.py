"""Exercise production handshake codecs with legacy readers, without launching Minecraft."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
bootstrap = (root / 'paper/src/main/java/de/maxhenkel/voicechat/VoicechatBootstrap.java').read_text(encoding='utf-8')
assert bootstrap.index('ServerBuildInfo.buildInfo()') < bootstrap.index('LifecycleEvents.COMMANDS'), 'Paper/Folia version must be checked before bootstrap command registration'
server_modules = ['common']
if (root / 'bukkit/src/main/java/de/maxhenkel/voicechat/voice/server/ServerVoiceEvents.java').exists():
    server_modules.append('bukkit')
for module in server_modules:
    guard = 'packet instanceof VersionPacket' if module == 'common' else 'p instanceof VersionPacket'
    net_path = root / f'{module}/src/main/java/de/maxhenkel/voicechat/net/NetManager.java'
    if net_path.exists():
        assert guard in net_path.read_text(encoding='utf-8'), f'{module} must allow version replies before voice protocol negotiation'
    server = (root / f'{module}/src/main/java/de/maxhenkel/voicechat/voice/server/ServerVoiceEvents.java').read_text(encoding='utf-8')
    assert 'VoiceVersion.matches' in server, f'{module} must compare exact releases and protocol numbers'
proxy_path = root / 'common-proxy/src/main/java/de/maxhenkel/voicechat/sniffer/VoiceProxySniffer.java'
if proxy_path.exists():
    proxy = proxy_path.read_text(encoding='utf-8')
    if 'VERSION_CHANNEL' in proxy:
        assert 'VoiceVersion.matches' in proxy, 'proxy must compare exact releases and protocol numbers'
base = 'de.maxhenkel.voicechat'
for module in server_modules:
    production = root / module / 'src/main/java/de/maxhenkel/voicechat'
    if not production.exists():
        continue
    out = root / f'build/version-tests/{module}'
    sources = []
    def stub(name, body):
        package, _ = name.rsplit('.', 1)
        p = out / 'stubs' / (name.replace('.', '/') + '.java')
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('package ' + package + ';\n' + body, encoding='utf-8')
        sources.append(str(p))
    buf = ('net.minecraft.network' if module == 'common' else base + '.util') + '.FriendlyByteBuf'
    stub(buf, '''import java.nio.*;import java.nio.charset.*;import java.util.*;
public class FriendlyByteBuf {
 private final ByteBuffer b=ByteBuffer.allocate(4096); public void flip(){b.flip();} public int remaining(){return b.remaining();}
 public boolean isReadable(){return b.hasRemaining();} public void writeInt(int n){b.putInt(n);} public int readInt(){return b.getInt();}
 public void writeByte(int n){b.put((byte)n);} public byte readByte(){return b.get();} public int readUnsignedByte(){return b.get()&255;}
 public void writeBoolean(boolean v){writeByte(v?1:0);} public boolean readBoolean(){return readByte()!=0;}
 public void writeDouble(double v){b.putDouble(v);} public double readDouble(){return b.getDouble();}
 public void writeUUID(UUID u){b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());} public UUID readUUID(){return new UUID(b.getLong(),b.getLong());}
 public void writeUtf(String s){writeUtf(s,32767);} public void writeUtf(String s,int max){if(s.length()>max)throw new IllegalArgumentException();byte[] bytes=s.getBytes(StandardCharsets.UTF_8);int n=bytes.length;while((n&~127)!=0){b.put((byte)(n&127|128));n>>>=7;}b.put((byte)n);b.put(bytes);}
 public String readUtf(){return readUtf(32767);} public String readUtf(int max){int n=0,shift=0,v;do{v=b.get()&255;n|=(v&127)<<shift;shift+=7;if(shift>35)throw new IllegalArgumentException();}while((v&128)!=0);if(n<0||n>max*4||n>b.remaining())throw new IllegalArgumentException();byte[] bytes=new byte[n];b.get(bytes);String s=new String(bytes,StandardCharsets.UTF_8);if(s.length()>max)throw new IllegalArgumentException();return s;}
}''')
    if module == 'common':
        # Both mapped Minecraft names are supported by the maintained source lines.
        text = (production / 'net/VersionPacket.java').read_text()
        resource = 'Identifier' if 'resources.Identifier;' in text else 'ResourceLocation'
        stub('net.minecraft.resources.' + resource, 'public class '+resource+' { public static '+resource+' fromNamespaceAndPath(String n,String p){return new '+resource+'();} }')
        stub('net.minecraft.network.protocol.common.custom.CustomPacketPayload', 'public interface CustomPacketPayload { record Type<T>(net.minecraft.resources.'+resource+' id){} Type<?> type(); }')
        stub('net.minecraft.server.level.ServerPlayer', 'public class ServerPlayer { public java.util.UUID getUUID(){return new java.util.UUID(1,2);} }')
        stub(base+'.Voicechat', 'public class Voicechat { public static final String MODID="voicechat"; }')
        player = 'new net.minecraft.server.level.ServerPlayer()'
    else:
        stub(base+'.util.Key', 'public class Key {}')
        stub('org.bukkit.entity.Player', 'public class Player { public java.util.UUID getUniqueId(){return new java.util.UUID(1,2);} }')
        stub(base+'.Voicechat', '''public class Voicechat { public static final Compatibility compatibility=new Compatibility();public static final Server SERVER=new Server();public static class Compatibility {public de.maxhenkel.voicechat.util.Key createNamespacedKey(String s){return new de.maxhenkel.voicechat.util.Key();}} public static class Server {public void onVersionPacket(org.bukkit.entity.Player p,de.maxhenkel.voicechat.net.VersionPacket v){} public void onRequestSecretPacket(org.bukkit.entity.Player p,de.maxhenkel.voicechat.net.RequestSecretPacket v){}} }''')
        player = 'new org.bukkit.entity.Player()'
    stub(base+'.config.ServerConfig', '''public class ServerConfig {
 public enum Codec { VOIP } public record Value<T>(T value){public T get(){return value;}}
 public Value<Codec> voiceChatCodec=new Value<>(Codec.VOIP);public Value<Integer> voiceChatMtuSize=new Value<>(1024),keepAlive=new Value<>(1000);
 public Value<Double> voiceChatDistance=new Value<>(48D);public Value<Boolean> groupsEnabled=new Value<>(true),allowRecording=new Value<>(false);
}''')
    stub(base+'.voice.common.Secret', 'public class Secret {public static Secret fromBytes('+buf+' b){for(int i=0;i<16;i++)b.readByte();return new Secret();}public void toBytes('+buf+' b){for(int i=0;i<16;i++)b.writeByte(i);}}')
    stub(base+'.net.VersionTest', '''import de.maxhenkel.voicechat.voice.transport.*;import de.maxhenkel.voicechat.config.*;import de.maxhenkel.voicechat.voice.common.*;
public class VersionTest {
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 public static void main(String[] args){
  for(int protocol:new int[]{20,1021,9999}){
   BUF raw=new BUF();raw.writeInt(protocol);raw.flip();RequestSecretPacket request=new RequestSecretPacket().fromBytes(raw);
   check(!request.hasTransportExtension()&&raw.remaining()==0,"legacy request fully consumed "+protocol);
  }
  BUF fallback=new BUF();new RequestSecretPacket(1021,3,"tcp,udp").withTransportExtension(false).toBytes(fallback);fallback.flip();
  check(fallback.remaining()==4&&fallback.readInt()==1021,"new client falls back to old server four-byte request");
  BUF modern=new BUF();new RequestSecretPacket(1021,3,"tcp,udp").toBytes(modern);modern.flip();
  check(new RequestSecretPacket().fromBytes(modern).hasTransportExtension()&&modern.remaining()==0,"modern request negotiated without new trailing fields");
  for(boolean extended:new boolean[]{false,true}){
   BUF raw=new BUF();new SecretPacket(PLAYER,new Secret(),24454,new ServerConfig(),"",20).withTransportExtension(extended).toBytes(raw);raw.flip();
   // Independent upstream/tcp.2 reader: it ends after allowRecording.
   for(int i=0;i<16;i++)raw.readByte();raw.readInt();raw.readUUID();raw.readByte();raw.readInt();raw.readDouble();raw.readInt();raw.readBoolean();raw.readUtf();raw.readBoolean();
   check(raw.remaining()==(extended?4:0),"secret layout matches negotiated legacy/modern reader "+extended);
   if(extended)check(raw.readInt()==20,"modern selected UDP protocol preserved");
  }
  BUF data=new BUF();new VersionPacket("2.6.24-tcp.3.2+26.2",1021).toBytes(data);data.flip();VersionPacket decoded=new VersionPacket().fromBytes(data);
  check(decoded.getVersion().equals("2.6.24-tcp.3.2+26.2")&&decoded.getProtocol()==1021&&data.remaining()==0,"independent version channel round trip");
  check(!VoiceVersion.matches("2.6.24-tcp.2+26.2",1021,"2.6.24-tcp.3.2+26.2",1021),"different TCP releases detected despite identical protocol");
  check(VoiceVersion.matches("2.6.24-tcp.3.2+1.21.4",1021,"2.6.24-tcp.3.2+26.3",1021),"Minecraft suffix does not produce a false mismatch across proxy versions");
  check(!VoiceVersion.matches("same-release",20,"same-release",1021),"protocol mismatch detected even when release labels match");
  check(!VoiceVersion.sanitize("fake\\nversion§c").contains("\\n"),"untrusted version cannot inject log lines");
  check(TransportMode.DUAL.select("udp,tcp",3)==VoiceProtocol.LEGACY_UDP,"UDP preference selects protocol 20");
  check(TransportMode.TCP.select("udp,tcp",1)==null,"no common transport remains incompatible");
 }
}'''.replace('BUF',buf).replace('PLAYER',player))
    sources += [str(production/'net'/f'{name}.java') for name in ('Packet','RequestSecretPacket','SecretPacket','VersionPacket')]
    transport = root / 'common/src/main/java/de/maxhenkel/voicechat/voice/transport'
    sources += [str(transport/f'{name}.java') for name in ('VoiceVersion','VoiceProtocol','VoiceEndpoint','TransportMode')]
    subprocess.run(['javac','--release','25','-encoding','UTF-8','-d',str(out),*sources],check=True)
    subprocess.run(['java','-ea','-cp',str(out),base+'.net.VersionTest'],check=True)
