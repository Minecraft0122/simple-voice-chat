"""Compile the real client lifecycle against minimal game/audio boundary doubles."""
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "build/client-tests"
stubs = out / "stubs"
stubs.mkdir(parents=True, exist_ok=True)
base = "de.maxhenkel.voicechat"
sources = []
def stub(name, body):
    package, simple = name.rsplit(".", 1)
    path = stubs / (name.replace(".", "/") + ".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("package " + package + ";\n" + body, encoding="utf-8")
    sources.append(str(path))
def vc(name, body): stub(base + "." + name, body)

stub("javax.annotation.Nullable", "public @interface Nullable {}")
stub("net.minecraft.ChatFormatting", "public enum ChatFormatting { DARK_RED }")
stub("net.minecraft.network.chat.Component", "public class Component { public static Component translatable(String key) { return new Component(); } public Component withStyle(Object o) { return this; } }")
stub("net.minecraft.client.player.LocalPlayer", "public class LocalPlayer { public void sendOverlayMessage(net.minecraft.network.chat.Component c) {} }")
stub("net.minecraft.client.Minecraft", "public class Minecraft { private static final Minecraft INSTANCE = new Minecraft(); public net.minecraft.client.player.LocalPlayer player; public static Minecraft getInstance() { return INSTANCE; } public synchronized void execute(Runnable r) { r.run(); } }")
vc("Voicechat", "public class Voicechat { public static final Logger LOGGER = new Logger(); public static class Logger { public void info(String s,Object... a) {} public void warn(String s,Object... a) {} public void error(String s,Object... a) {} public void debug(String s,Object... a) {} } }")
vc("VoicechatClient", "public class VoicechatClient { public static final Config CLIENT_CONFIG = new Config(); public static class Config { public Flag useNatives = new Flag(); } public static class Flag { public boolean get() { return false; } } }")
vc("debug.CooldownTimer", "public class CooldownTimer { public static void run(String s,Runnable r) { r.run(); } }")
vc("debug.VoicechatUncaughtExceptionHandler", "public class VoicechatUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler { public void uncaughtException(Thread t,Throwable e) { throw new AssertionError(e); } }")
vc("gui.onboarding.OnboardingManager", "public class OnboardingManager { public static void onConnecting() {} }")
vc("natives.ClientNativeManager", "public class ClientNativeManager { public static void onConnecting() {} }")
vc("intercompatibility.ClientCompatibilityManager", "public class ClientCompatibilityManager { public static final ClientCompatibilityManager INSTANCE = new ClientCompatibilityManager(); public Runnable disconnected = () -> {}; public java.util.function.Consumer<de.maxhenkel.voicechat.voice.client.ClientVoicechatConnection> connected = c -> {}; public void emitVoiceChatDisconnectedEvent() { disconnected.run(); } public void emitVoiceChatConnectedEvent(de.maxhenkel.voicechat.voice.client.ClientVoicechatConnection c) { connected.accept(c); } }")
vc("plugins.ClientPluginManager", "public class ClientPluginManager { private static final ClientPluginManager I = new ClientPluginManager(); public static ClientPluginManager instance() { return I; } public boolean onStartMic() { return false; } public de.maxhenkel.voicechat.api.ClientVoicechatSocket getClientSocketImplementation() { return new de.maxhenkel.voicechat.voice.client.TestSocket(); } }")
vc("voice.client.speaker.SpeakerException", "public class SpeakerException extends Exception {}")
vc("voice.client.SoundManager", "public class SoundManager { public static SoundManager create() throws de.maxhenkel.voicechat.voice.client.speaker.SpeakerException { return new SoundManager(); } public void close() {} }")
vc("voice.client.ClientManager", "public class ClientManager { public static State getPlayerStateManager() { return new State(); } public static class State { public boolean isDisabled() { return false; } public void onSpeakerAvailabilityChanged() {} } }")
vc("voice.client.TalkCache", "public class TalkCache {}")
vc("voice.client.ChatUtils", "public class ChatUtils { public static void sendModMessage(net.minecraft.network.chat.Component c) {} public static void sendModErrorMessage(String key,Exception e) {} }")
vc("voice.client.AudioChannel", "public class AudioChannel extends Thread { public AudioChannel(ClientVoicechat c,InitializationData i,SoundManager s,java.util.UUID id) {} public void addToQueue(de.maxhenkel.voicechat.voice.common.SoundPacket p) {} public boolean canKill() { return false; } public void closeAndKill() {} public boolean isClosed() { return false; } }")
vc("voice.client.AudioRecorder", "public class AudioRecorder { public static AudioRecorder create() { return new AudioRecorder(); } public void saveAndClose() {} }")
vc("voice.client.MicThread", "public class MicThread extends Thread { public MicThread(ClientVoicechat c,ClientVoicechatConnection connection,java.util.function.Consumer<Exception> error) {} public void close() {} }")
vc("voice.client.InitializationData", "public class InitializationData { public String getServerIP() { return \"127.0.0.1\"; } public int getServerPort() { return 24454; } public boolean allowRecording() { return false; } public int getKeepAlive() { return 1000; } public java.util.UUID getPlayerUUID() { return new java.util.UUID(1,2); } public de.maxhenkel.voicechat.voice.common.Secret getSecret() { return de.maxhenkel.voicechat.voice.common.Secret.fromBytes(new byte[16]); } }")
vc("voice.common.Packet", "public interface Packet<T> {}")
vc("voice.common.Secret", "public class Secret { private byte[] bytes; public static Secret fromBytes(byte[] b) { Secret s = new Secret(); s.bytes = b; return s; } public byte[] getSecret() { return bytes; } }")
vc("voice.common.NetworkMessage", "public class NetworkMessage { private Packet<?> packet; public NetworkMessage(Packet<?> p) { packet=p; } public Packet<?> getPacket() { return packet; } }")
vc("voice.common.Utils", "public class Utils { public static void sleep(long n) { try { Thread.sleep(n); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } }")
for name in ("AuthenticateAckPacket", "ConnectionCheckPacket", "ConnectionCheckAckPacket", "KeepAlivePacket"):
    vc("voice.common."+name, "public class "+name+" implements Packet<"+name+"> {}")
vc("voice.common.PingPacket", "public class PingPacket implements Packet<PingPacket> { public int getId() { return 1; } }")
vc("voice.common.SoundPacket", "public class SoundPacket<T> implements Packet<SoundPacket<T>> { public java.util.UUID getChannelId() { return new java.util.UUID(1,2); } }")
vc("voice.common.MicPacket", "public class MicPacket implements Packet<MicPacket> { public MicPacket(byte[] data,boolean whisper,long sequence) {} }")
vc("voice.common.AuthenticatePacket", "public class AuthenticatePacket implements Packet<AuthenticatePacket> { public AuthenticatePacket(java.util.UUID p,Secret s) {} }")
for name in ("AuthenticationChallengePacket", "AuthenticationResponsePacket"):
    vc("voice.common."+name, "public class "+name+" implements Packet<"+name+"> { private byte[] data; public "+name+"(byte[] d) { data=d; } public byte[] getData() { return data; } }")
vc("voice.common.AvailabilityPacket", "public class AvailabilityPacket implements Packet<AvailabilityPacket> { private int status; public AvailabilityPacket(int s) { status=s; } public int getStatus() { return status; } }")
vc("voice.client.ClientNetworkMessage", "public class ClientNetworkMessage { public static final java.util.List<de.maxhenkel.voicechat.voice.common.Packet<?>> sent = new java.util.concurrent.CopyOnWriteArrayList<>(); public static de.maxhenkel.voicechat.voice.common.NetworkMessage readPacketClient(de.maxhenkel.voicechat.api.RawUdpPacket p,ClientVoicechatConnection c) { return ((TestSocket.Incoming)p).message(); } public static byte[] writeClient(ClientVoicechatConnection c,de.maxhenkel.voicechat.voice.common.NetworkMessage m) { sent.add(m.getPacket()); return new byte[]{1}; } }")

production = root / "common-client/src/main/java/de/maxhenkel/voicechat/voice/client"
sources += [str(production / name) for name in ("ClientVoicechat.java", "ClientVoicechatConnection.java")]
sources += [str(root / "common/src/main/java/de/maxhenkel/voicechat/voice/common/NamedThreadPoolFactory.java")]
sources += [str(root / "common/src/main/java/de/maxhenkel/voicechat/voice/transport" / name) for name in ("VoiceAvailability.java", "VoiceHandshake.java")]
sources += [str(root / "api/src/main/java/de/maxhenkel/voicechat/api" / name) for name in ("ClientVoicechatSocket.java", "RawUdpPacket.java")]
sources += [str(root / "tests/client" / name) for name in ("TestSocket.java", "ClientLifecycleTest.java")]
subprocess.run([shutil.which("javac"), "--release", "25", "-encoding", "UTF-8", "-d", str(out), *sources], check=True)
subprocess.run([shutil.which("java"), "-ea", "-cp", str(out), "de.maxhenkel.voicechat.voice.client.ClientLifecycleTest"], check=True)
