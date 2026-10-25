"""Exercise production scheduling methods with a Folia-style rejecting main executor.

Only the small scheduling methods are extracted verbatim, so the test needs no
Minecraft download. Platform adapters and routing work are explicit doubles;
this does not launch Folia or claim to test player audio/region access.
"""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
server = (root / 'common/src/main/java/de/maxhenkel/voicechat/voice/server/Server.java').read_text(encoding='utf-8')
paper = (root / 'paper/src/main/java/de/maxhenkel/voicechat/intercompatibility/PaperCommonCompatibilityManager.java').read_text(encoding='utf-8')

def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

process_run = method(server[server.index('private class ProcessThread'):], 'public void run()')
assert 'scheduleProxyRoutingUpdate();' in process_run, 'Production processing loop must use the tested scheduler entry point'
assert 'server.execute(' not in process_run, 'Folia rejects the Minecraft main executor'
assert 'routingUpdateQueued.compareAndSet(false, true)' in process_run, 'Only one routing batch may be outstanding'

source = (root / 'tests/folia/FoliaRoutingSchedulerTest.java').read_text(encoding='utf-8')
source = source.replace('// PRODUCTION_ROUTING_METHOD', method(server, 'private void scheduleProxyRoutingUpdate()'))
source = source.replace('// PRODUCTION_PLATFORM_METHOD', method(paper, 'public void execute(MinecraftServer server, Runnable runnable)'))
out = root / 'build/folia-scheduler-tests'
out.mkdir(parents=True, exist_ok=True)
java = out / 'FoliaRoutingSchedulerTest.java'
java.write_text(source, encoding='utf-8')
subprocess.run(['javac', '--release', '25', '-encoding', 'UTF-8', '-d', str(out), str(java)], check=True)
subprocess.run(['java', '-ea', '-cp', str(out), 'FoliaRoutingSchedulerTest'], check=True)
