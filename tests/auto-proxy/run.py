"""Exercise actual backend authentication/config handlers against platform scheduling boundaries."""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parents[2]
transport = root/'common/src/main/java/de/maxhenkel/voicechat/voice/transport'

def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace+1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

for module in ('common', 'bukkit'):
    base = root/module/'src/main/java/de/maxhenkel/voicechat'
    source = (base/'voice/server/ServerVoiceEvents.java').read_text(encoding='utf-8')
    player = 'ServerPlayer' if module == 'common' else 'Player'
    methods = '\n'.join(method(source, signature) for signature in (
        f'public void onRequestSecretPacket({player}', f'private void requestProxySettings({player}', f'public void onProxySettings({player}'))
    net = (base/'net/NetManager.java').read_text(encoding='utf-8')
    assert 'instanceof ProxyControlPacket' in net, 'Config requests must bypass voice compatibility gate'
    control = method((base/'voice/server/Server.java').read_text(encoding='utf-8'), f'public void handleProxyControl({player}')
    assert control.index('CONFIG_RESPONSE') < control.index('SERVER.isCompatible'), 'Config responses must work before voice auth'
    for loader in ('fabric', 'neoforge', 'quilt'):
        loader_source = next((root/loader/'src/main/java/de/maxhenkel/voicechat/net').glob('*NetManager.java')).read_text(encoding='utf-8')
        assert 'packetType.equals(ProxyControlPacket.class) && Voicechat.SERVER_CONFIG.isProxyMode()' in loader_source
    harness = '''package de.maxhenkel.voicechat.voice.transport;
import java.util.*;import java.util.concurrent.*;
public class BackendSyncTest {
    final ProxyConfigSync proxyConfigSync = new ProxyConfigSync();
    final Map<UUID, RequestSecretPacket> pendingProxyRequests = new ConcurrentHashMap<>();
    Server server = new Server();
    int authenticated;
    void processSecretRequest(PLAYER player, RequestSecretPacket packet) { authenticated++; }
    METHODS
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    public static void main(String[] args) {
        var test = new BackendSyncTest(); var player = new PLAYER();
        test.onRequestSecretPacket(player, new RequestSecretPacket());
        check(test.authenticated == 1 && NetManager.last == null, "standalone needs no voice proxy");
        test.authenticated = 0; Voicechat.SERVER_CONFIG.proxy = true;
        test.onRequestSecretPacket(player, new RequestSecretPacket());
        check(test.authenticated == 0, "never issue a voice secret before synchronized settings");
        var request = ProxyMessages.decode(NetManager.last);
        check(request.type() == ProxyMessages.CONFIG_REQUEST, "backend initiates synchronization");
        ProxySettings settings = new ProxySettings(TransportMode.UDP, "udp,tcp", 24460, "public.test");
        test.onProxySettings(player, new ProxyMessages.Message(ProxyMessages.CONFIG_RESPONSE, 0L, UUID.randomUUID(), settings.encode()));
        check(test.authenticated == 0, "unsolicited response cannot authenticate");
        var response = new ProxyMessages.Message(ProxyMessages.CONFIG_RESPONSE, 0L, request.session(), settings.encode());
        test.onProxySettings(player, response);
        check(test.authenticated == 1 && Voicechat.SERVER_CONFIG.settings.equals(settings), "valid settings applied before authentication");
        test.onProxySettings(player, response);
        check(test.authenticated == 1, "replayed response ignored");
        test.onRequestSecretPacket(player, new RequestSecretPacket());
        var pending = ProxyMessages.decode(NetManager.last);
        check(test.authenticated == 1, "every new negotiation refreshes network authority");
        test.proxyConfigSync.expire(player.id, pending.session());
        test.onProxySettings(player, new ProxyMessages.Message(ProxyMessages.CONFIG_RESPONSE, 0L, pending.session(), settings.encode()));
        check(test.authenticated == 1, "late response cannot resume expired negotiation");
        System.out.println("MODULE backend configuration/authentication checks passed");
    }
    static class Player { final UUID id=UUID.randomUUID(); UUID getUniqueId(){return id;}
        boolean isOnline(){return true;} void sendMessage(String s){} }
    static class ServerPlayer extends Player { UUID getUUID(){return id;} void sendSystemMessage(Component c){} }
    static class RequestSecretPacket {}
    record ProxyControlPacket(byte[] data) {}
    static class Server { Object getServer(){return this;} }
    record Component(String value) {static Component literal(String s){return new Component(s);}}
    static class NetManager {static byte[] last;static void sendToClient(Player p, ProxyControlPacket packet){last=packet.data();}}
    static class Voicechat {static final Config SERVER_CONFIG=new Config();static final Logger LOGGER=new Logger();
        static final Compatibility compatibility=new Compatibility();}
    static class Config { boolean proxy; ProxySettings settings;boolean isProxyMode(){return proxy;}
        void applyProxySettings(ProxySettings value){settings=value;} }
    static class Logger {void info(String s,Object... args){} void warn(String s,Object... args){}}
    static class Compatibility {void runTaskLater(Runnable r,long ticks){}}
    static class CommonCompatibilityManager {static final CommonCompatibilityManager INSTANCE=new CommonCompatibilityManager();
        void executeForPlayer(Object server, ServerPlayer player,Runnable action,Runnable retired){action.run();}}
}'''.replace('METHODS', methods).replace('PLAYER', player).replace('MODULE', module)
    out = root/f'build/auto-proxy-tests/{module}'
    out.mkdir(parents=True, exist_ok=True)
    target = out/'BackendSyncTest.java'
    target.write_text(harness, encoding='utf-8')
    sources = [str(transport/(name+'.java')) for name in ('ProxySettings','ProxyConfigSync','ProxyMessages','TransportMode','VoiceProtocol','VoiceEndpoint')]
    subprocess.run(['javac','--release','25','-encoding','UTF-8','-d',str(out),str(target),*sources],check=True)
    subprocess.run(['java','-ea','-cp',str(out),'de.maxhenkel.voicechat.voice.transport.BackendSyncTest'],check=True)
