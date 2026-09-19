param(
    [string] $JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$OutputDirectory = Join-Path $RepositoryRoot 'build/proxy-tests'
$SourceDirectory = Join-Path $RepositoryRoot 'common-proxy/src/main/java/de/maxhenkel/voicechat/network'
$TestFile = Join-Path $RepositoryRoot 'tests/proxy/ProxyVoicePacketCodecTest.java'

if ($JavaHome) {
    $JavaCompiler = Join-Path $JavaHome 'bin/javac.exe'
    $JavaRuntime = Join-Path $JavaHome 'bin/java.exe'
} else {
    $JavaCompiler = (Get-Command javac -ErrorAction Stop).Source
    $JavaRuntime = (Get-Command java -ErrorAction Stop).Source
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$SourceFiles = @(
    (Join-Path $SourceDirectory 'ProxyVoicePacketCodec.java'),
    $TestFile
)
& $JavaCompiler --release 25 -encoding UTF-8 -d $OutputDirectory @SourceFiles
if ($LASTEXITCODE -ne 0) { throw "Proxy codec test compilation failed (exit $LASTEXITCODE)." }
& $JavaRuntime -ea -cp $OutputDirectory de.maxhenkel.voicechat.network.ProxyVoicePacketCodecTest
if ($LASTEXITCODE -ne 0) { throw "Proxy codec regression tests failed (exit $LASTEXITCODE)." }
