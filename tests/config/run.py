"""Exercise production config classes using the real config writer and platform boundary doubles."""
from pathlib import Path
import os, subprocess
root=Path(__file__).resolve().parents[2]
cache=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
def jar(group, artifact):
 matches=[p for p in (cache/group/artifact).glob('*/*/*.jar') if not any(x in p.name for x in ('sources','javadoc'))]
 if not matches: raise SystemExit(f'Missing {artifact}; run Gradle :bukkit:compileJava first')
 return str(sorted(matches)[-1])
cp=os.pathsep.join([jar('de.maxhenkel.configbuilder','configbuilder'),jar('org.slf4j','slf4j-api')])
for module in ('common','bukkit'):
 out=root/f'build/config-tests/{module}'
 stubs=out/'stubs';stubs.mkdir(parents=True,exist_ok=True);sources=[]
 def stub(name,body):
  package,_=name.rsplit('.',1);p=stubs/(name.replace('.','/')+'.java');p.parent.mkdir(parents=True,exist_ok=True)
  p.write_text('package '+package+';\n'+body,encoding='utf-8');sources.append(str(p))
 stub('de.maxhenkel.voicechat.Voicechat','''public class Voicechat {
 public static final Voicechat INSTANCE=new Voicechat();public static final Logger LOGGER=new Logger();
 public static class Logger { public void info(String s,Object... args){} }
 public enum Loader {PAPER,FABRIC,NEOFORGE} public Description getDescription(){return new Description();}
 public static class Description {public String getVersion(){return "test";}}
 }''')
 stub('de.maxhenkel.voicechat.BuildConstants','public class BuildConstants {public static final String PLUGIN_NAME="test",MOD_VERSION="test";}')
 stub('de.maxhenkel.voicechat.voice.common.AudioUtils','public class AudioUtils {public static final int MAX_OPUS_PAYLOAD_SIZE=1275;}')
 stub('de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager','public class CommonCompatibilityManager {public static final CommonCompatibilityManager INSTANCE=new CommonCompatibilityManager();public String getModName(){return "test";}public String getModVersion(){return "test";}}')
 stub('de.maxhenkel.opus4j.OpusEncoder','public class OpusEncoder {public enum Application {VOIP,AUDIO,LOW_DELAY}}')
 stub('de.maxhenkel.voicechat.api.opus.OpusEncoderMode','public enum OpusEncoderMode {VOIP,AUDIO,RESTRICTED_LOWDELAY}')
 stub('org.spigotmc.SpigotConfig','public class SpigotConfig {public static boolean bungee;}')
 stub('com.destroystokyo.paper.PaperConfig','public class PaperConfig {public static boolean velocitySupport;}')
 stub('io.papermc.paper.configuration.GlobalConfiguration','''public class GlobalConfiguration {
 public static final GlobalConfiguration INSTANCE=new GlobalConfiguration(); public static GlobalConfiguration get(){return INSTANCE;}
 public Proxies proxies=new Proxies();public static class Proxies {public Velocity velocity=new Velocity();}
 public static class Velocity {public boolean enabled;}
 }''')
 production=root/f'{module}/src/main/java/de/maxhenkel/voicechat/config/ServerConfig.java'
 sources.append(str(production))
 sources.append(str(root/'common-proxy/src/main/java/de/maxhenkel/voicechat/config/ProxyConfig.java'))
 for name in ('ProxyEnvironment','TransportMode','VoiceProtocol','VoiceEndpoint'):
  sources.append(str(root/f'common/src/main/java/de/maxhenkel/voicechat/voice/transport/{name}.java'))
 factory='b -> new ServerConfig(b, Voicechat.Loader.FABRIC)' if module=='common' else 'ServerConfig::new'
 test=(root/'tests/config/ConfigTest.java').read_text(encoding='utf-8').replace('CONFIG_FACTORY',factory)
 target=out/'ConfigTest.java';target.write_text(test,encoding='utf-8');sources.append(str(target))
 subprocess.run(['javac','--release','25','-encoding','UTF-8','-cp',cp,'-d',str(out),*sources],check=True)
 subprocess.run(['java','-ea','-cp',str(out)+os.pathsep+cp,'de.maxhenkel.voicechat.voice.transport.ConfigTest',str(out)],check=True)
 print(f'{module}: generated configuration + proxy detection checks passed',flush=True)
