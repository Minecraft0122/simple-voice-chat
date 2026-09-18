param(
    [string] $JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$OutputDirectory = Join-Path $RepositoryRoot 'build/transport-tests'
$TransportDirectory = Join-Path $RepositoryRoot 'common/src/main/java/de/maxhenkel/voicechat/voice/transport'
$TestDirectory = Join-Path $RepositoryRoot 'tests/transport'
$ApiDirectory = Join-Path $RepositoryRoot 'api/src/main/java/de/maxhenkel/voicechat/api'
$CommonImplDirectory = Join-Path $RepositoryRoot 'common/src/main/java/de/maxhenkel/voicechat/plugins/impl'
$ClientImplDirectory = Join-Path $RepositoryRoot 'common-client/src/main/java/de/maxhenkel/voicechat/plugins/impl'

if ($JavaHome) {
    $JavaCompiler = Join-Path $JavaHome 'bin/javac.exe'
    $JavaRuntime = Join-Path $JavaHome 'bin/java.exe'
} else {
    $JavaCompiler = (Get-Command javac -ErrorAction Stop).Source
    $JavaRuntime = (Get-Command java -ErrorAction Stop).Source
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$SourceFiles = @(
    (Join-Path $TransportDirectory 'TcpFrameCodec.java'),
    (Join-Path $TransportDirectory 'TcpConnection.java'),
    (Join-Path $TransportDirectory 'TcpServer.java'),
    # Compile the real, dependency-free client adapter and packet envelope too.
    (Join-Path $ApiDirectory 'ClientVoicechatSocket.java'),
    (Join-Path $ApiDirectory 'RawUdpPacket.java'),
    (Join-Path $CommonImplDirectory 'RawUdpPacketImpl.java'),
    (Join-Path $ClientImplDirectory 'ClientVoicechatSocketImpl.java'),
    (Join-Path $TestDirectory 'TcpTransportTest.java')
)
foreach ($SourceFile in $SourceFiles) {
    if (-not (Test-Path -LiteralPath $SourceFile -PathType Leaf)) {
        throw "Missing transport source: $SourceFile"
    }
}

& $JavaCompiler --release 25 -encoding UTF-8 -d $OutputDirectory @SourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "TCP transport test compilation failed (exit $LASTEXITCODE)."
}
& $JavaRuntime -ea -cp $OutputDirectory TcpTransportTest
if ($LASTEXITCODE -ne 0) {
    throw "TCP transport regression tests failed (exit $LASTEXITCODE)."
}
