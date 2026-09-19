#!/usr/bin/env sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output_directory="$repository_root/build/proxy-tests"
source_directory="$repository_root/common-proxy/src/main/java/de/maxhenkel/voicechat/network"
mkdir -p "$output_directory"
javac --release 25 -encoding UTF-8 -d "$output_directory" \
    "$source_directory/ProxyVoicePacketCodec.java" \
    "$repository_root/tests/proxy/ProxyVoicePacketCodecTest.java"
java -ea -cp "$output_directory" de.maxhenkel.voicechat.network.ProxyVoicePacketCodecTest
