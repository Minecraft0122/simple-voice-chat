#!/usr/bin/env sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out="$root/build/runtime-tests"
proxy="$root/common-proxy/src/main/java/de/maxhenkel/voicechat"
transport="$root/common/src/main/java/de/maxhenkel/voicechat/voice/transport"
mkdir -p "$out"
javac --release 25 -encoding UTF-8 -d "$out" \
    "$proxy/network/ProxyTcpConnection.java" "$proxy/network/VoiceProxyServer.java" "$proxy/network/ProxyVoicePacketCodec.java" \
    "$proxy/sniffer/VoiceProxySniffer.java" "$proxy/sniffer/SniffedSecretPacket.java" "$proxy/sniffer/IncompatibleVoiceChatException.java" \
    "$proxy/util/ByteBufferWrapper.java" "$proxy/util/VarIntUtils.java" "$proxy/logging/VoiceChatLogger.java" "$proxy/logging/LogLevel.java" \
    "$transport/TcpConnection.java" "$transport/TcpFrameCodec.java" "$transport/VoiceHandshake.java" "$transport/VoiceAvailability.java" "$transport/ProxyMessages.java" \
    "$root/tests/proxy/stubs/de/maxhenkel/voicechat/VoiceProxy.java" "$root/tests/proxy/ProxyRuntimeTest.java"
java -ea -cp "$out" de.maxhenkel.voicechat.network.ProxyRuntimeTest
