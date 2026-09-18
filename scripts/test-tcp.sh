#!/usr/bin/env sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output_directory="$repository_root/build/transport-tests"
transport_directory="$repository_root/common/src/main/java/de/maxhenkel/voicechat/voice/transport"
api_directory="$repository_root/api/src/main/java/de/maxhenkel/voicechat/api"
common_impl_directory="$repository_root/common/src/main/java/de/maxhenkel/voicechat/plugins/impl"
client_impl_directory="$repository_root/common-client/src/main/java/de/maxhenkel/voicechat/plugins/impl"

if [ -n "${JAVA_HOME:-}" ]; then
    java_compiler="$JAVA_HOME/bin/javac"
    java_runtime="$JAVA_HOME/bin/java"
else
    java_compiler=javac
    java_runtime=java
fi

mkdir -p "$output_directory"
"$java_compiler" --release 25 -encoding UTF-8 -d "$output_directory" \
    "$transport_directory/TcpFrameCodec.java" \
    "$transport_directory/TcpConnection.java" \
    "$transport_directory/TcpServer.java" \
    "$api_directory/ClientVoicechatSocket.java" \
    "$api_directory/RawUdpPacket.java" \
    "$common_impl_directory/RawUdpPacketImpl.java" \
    "$client_impl_directory/ClientVoicechatSocketImpl.java" \
    "$repository_root/tests/transport/TcpTransportTest.java"
"$java_runtime" -ea -cp "$output_directory" TcpTransportTest
