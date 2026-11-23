"""Verify the newest published Fabric client for this exact MC release, without launching Minecraft.

Run in GitHub Actions. Download from the author's Modrinth project, verify its
published SHA-512, and inspect the real BuildConstants bytecode. A new upstream
wire protocol deliberately fails this gate instead of being silently treated as 20.
"""
from pathlib import Path
import hashlib, json, re, subprocess, time, urllib.request, zipfile

root = Path(__file__).resolve().parents[2]
mc = next(line.split('=', 1)[1] for line in (root/'gradle.properties').read_text().splitlines() if line.startswith('minecraft_version='))
out = root/'build/upstream-client-check'
out.mkdir(parents=True, exist_ok=True)

def fetch(url):
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'TCP-Voice-Chat-compatibility-CI'})
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except OSError:
            if attempt == 2: raise
            time.sleep(2)

versions = json.loads(fetch('https://api.modrinth.com/v2/project/9eGKb6K1/version'))
versions = [v for v in versions if 'fabric' in v['loaders'] and mc in v['game_versions'] and v['version_number'].endswith('+'+mc)]
if not versions: raise SystemExit('No published official Fabric client for '+mc)
latest = max(versions, key=lambda v: v['date_published'])
file = next(f for f in latest['files'] if f.get('primary'))
jar = out/'official-client.jar'
data = fetch(file['url'])
assert hashlib.sha512(data).hexdigest() == file['hashes']['sha512'], 'Official client checksum mismatch'
jar.write_bytes(data)
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    assert 'de/maxhenkel/voicechat/BuildConstants.class' in archive.namelist()
bytecode = subprocess.check_output(['javap', '-c', '-p', '-classpath', str(jar), 'de.maxhenkel.voicechat.BuildConstants'], text=True)
assert re.search(r'// String 20(?:\s|$)', bytecode), 'Newest official client no longer uses protocol 20; implement its wire codec before claiming compatibility.\n'+bytecode
assert 'Field COMPATIBILITY_VERSION:I' in bytecode
report = f"Official client: {latest['version_number']}\nPublished: {latest['date_published']}\nType: {latest['version_type']}\nWire protocol: 20\nSHA512: {file['hashes']['sha512']}\nScope: published artifact metadata/bytecode; handshake and mixed audio routing tested separately, not a live Minecraft session.\n"
(out/'compatibility-report.txt').write_text(report, encoding='utf-8')
print(report)
