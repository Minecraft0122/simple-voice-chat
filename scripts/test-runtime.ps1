param([string] $JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$OutputDirectory = Join-Path $RepositoryRoot 'build/runtime-tests'
$ProxySource = Join-Path $RepositoryRoot 'common-proxy/src/main/java/de/maxhenkel/voicechat'
$TransportSource = Join-Path $RepositoryRoot 'common/src/main/java/de/maxhenkel/voicechat/voice/transport'
$JavaCompiler = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { (Get-Command javac).Source }
$JavaRuntime = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java).Source }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$Sources = @(
    "$ProxySource/network/ProxyTcpConnection.java", "$ProxySource/network/VoiceProxyServer.java",
    "$ProxySource/network/ProxyVoicePacketCodec.java", "$ProxySource/sniffer/VoiceProxySniffer.java",
    "$ProxySource/sniffer/SniffedSecretPacket.java", "$ProxySource/sniffer/IncompatibleVoiceChatException.java",
    "$ProxySource/util/ByteBufferWrapper.java", "$ProxySource/util/VarIntUtils.java",
    "$ProxySource/logging/VoiceChatLogger.java", "$ProxySource/logging/LogLevel.java",
    "$TransportSource/TcpConnection.java", "$TransportSource/TcpFrameCodec.java",
    "$TransportSource/VoiceHandshake.java", "$TransportSource/VoiceAvailability.java", "$TransportSource/ProxyMessages.java",
    "$RepositoryRoot/tests/proxy/stubs/de/maxhenkel/voicechat/VoiceProxy.java",
    "$RepositoryRoot/tests/proxy/ProxyRuntimeTest.java"
)
& $JavaCompiler --release 25 -encoding UTF-8 -d $OutputDirectory @Sources
if ($LASTEXITCODE -ne 0) { throw 'Runtime test compilation failed' }
& $JavaRuntime -ea -cp $OutputDirectory de.maxhenkel.voicechat.network.ProxyRuntimeTest
if ($LASTEXITCODE -ne 0) { throw 'Runtime regression tests failed' }
