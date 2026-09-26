# Encryption and audio codec evaluation — 2026-09-26

Decision: retain AES-128-GCM and Opus. Add connection-bound challenge authentication,
HKDF-SHA256 directional session keys and explicit audio availability in protocol 1021.

## Local encryption/decryption comparison

JDK 25.0.2 on this Windows amd64 host. `java tests/bench/CryptoBenchmark.java` uses
10,000 warmup pairs per size/algorithm, then five samples of 20,000 encrypt/decrypt pairs,
alternating order. It includes fresh JCA Cipher objects and random nonces as used by the
current implementation. The table reports the median microseconds for encryption plus
decryption, not a per-player end-to-end latency estimate or a portable JMH result.

| Payload | AES-128-GCM | ChaCha20-Poly1305 |
| --- | ---: | ---: |
| 128 bytes | 11.292 µs | 11.594 µs |
| 512 bytes | 11.857 µs | 16.817 µs |
| 1275 bytes | 13.259 µs | 23.070 µs |

No local performance benefit justified changing the wire cipher. ChaCha20-Poly1305 can
be attractive on systems without AES acceleration; this benchmark does not claim the
same result on ARM or every client machine. Its hardware-dependent tradeoff is discussed
in [RFC 8439](https://www.rfc-editor.org/rfc/rfc8439.html#section-1).

## Audio compression/decompression

The payload already uses Opus, a codec designed for interactive speech/music and game
chat, with adjustable bitrate and frame duration. See the
[Opus project](https://opus-codec.org/) and
[codec specification](https://www.rfc-editor.org/rfc/rfc6716.html).
This review found no demonstrated replacement that improves this project's combination
of speech quality, bandwidth, decoder availability and latency. No alternate audio codec
was benchmarked with a listening corpus, so no universal speed or quality superiority
is claimed. Keep Opus, avoid adding a second compression layer to these already encoded
small frames, and reduce redundant metadata instead.

## Security boundaries

Changing algorithms would not fix replayable authentication. The new challenge proof is
bound to the player and one TCP connection; fresh HKDF-derived keys isolate reconnections
and separate directions. See [HKDF, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.html).
Captured old authentication proofs cannot replace a live connection, and a repeated
handshake cannot reset its microphone replay counter. The underlying shared secret is
still delivered through Minecraft, so this design does not provide forward secrecy or
hide audio from the proxy operator. TLS 1.3 with authenticated certificates would be a
separate deployment/protocol change, not merely a faster encryption algorithm.
