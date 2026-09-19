# TCP voice transport

This fork targets Minecraft 26.1, 26.2 and 26.3 in separate `tcp/26.x` branches.
It retains the upstream license, codecs, encryption, application packets, game-channel
initialization, group management and public API names. It is not wire compatible with
the upstream UDP transport. Compatibility version `1020` distinguishes it during the
Minecraft-channel handshake; install this fork on both ends.

## Wire format

Each TCP connection carries a sequence of frames. A frame is a **4-byte big-endian
signed integer length**, followed by exactly that many bytes of the original encrypted
voice packet. Legal lengths are 1 through 4096 inclusive. The existing voice packet
payload limits still apply after decryption. The decoder rejects invalid lengths
before allocating the payload and closes truncated or timed-out streams. Multiple
frames in one TCP read and frames split across TCP reads are both valid.

The existing authentication exchanges remain inside these frames. A connection must
authenticate using the player's secret delivered through Minecraft networking before
its audio can be routed. `RawUdpPacket` is a historical API name for the packet envelope;
it does not indicate UDP traffic in this fork. Custom socket implementations must use
the same TCP framing on both ends.

## Network and lifecycle

The voice listener uses a separate TCP port, default `24454`. `voice_host` advertises
an external address/port in the same way as upstream. Minecraft's TCP listener cannot
share its port with this listener. Legacy `port=-1` maps to `24454`; explicit selection
of the game port is rejected with an actionable error. Integrated servers choose a
free voice port and retain it when the game is opened to LAN.

Connections enable TCP_NODELAY and TCP keepalive. Each peer has an independent bounded
writer queue so a client that stops reading cannot stall audio delivery to everyone
else. Oversized frames, write deadlines and queue exhaustion terminate the affected
connection. Incoming packet queues and accepted connection counts are also bounded.
Reads enforce an inactivity timeout and a separate deadline for completing a frame.
The inactivity timeout allows for the configured application keepalive interval.

Client connect attempts have a finite timeout. Closing a connection wakes waiting
readers and cancels pending connection establishment. Socket EOF/errors trigger voice
disconnect cleanup. Logout and application timeouts close the player's TCP socket;
reauthentication on a new TCP endpoint replaces the old endpoint. Server shutdown
closes the listener and all client streams and unblocks a pending receive.

## Scope

Fabric, NeoForge, Bukkit and Paper share the modified transport. Forge is additionally targeted
by the 26.1 and 26.2 branches; upstream's 26.3 branch has no 26.3 Forge implementation.
The fork does not support the upstream UDP voice proxies or WebRTC transport. The 26.3
branch includes a central TCP implementation for Velocity and BungeeCord. Enable
`proxy_mode=true` on every backend server. In this mode a backend does not open a player
voice listener; it sends low-rate `voicechat:proxy_routing` messages containing the
player's connected state, distances and target UUIDs. Each update also carries a random
backend generation and monotonic update sequence so stale switch/reconnect state is
ignored. The proxy owns the public TCP
listener, authenticates clients, decrypts each microphone packet, applies the backend
routing state and encrypts a `PlayerSoundPacket` separately for each target. This removes
the per-player audio stream between the proxy and every backend server. Backends must be
connected to the same proxy and must use the matching 26.3 fork.

The proxy listens on `24454/TCP` by default. Its game port and voice port must be
different. The proxy is a trusted plaintext boundary because it must hold each player's
AES-GCM key. The fork retains AES-GCM with random 12-byte IVs and 128-bit tags; it adds
defensive key handling, ciphertext length checks, constant-time secret comparison and
strictly increasing microphone sequence checks at the central endpoint. This is not TLS,
does not provide forward secrecy, and offline Minecraft mode still requires a separately
protected game/proxy connection. Proxy mode transfers the built-in distance, visibility
and group target sets; addons that replace microphone routing or listener decisions must
be adapted to provide equivalent proxy metadata.

The 26.1 and 26.2 branches retain the TCP backend transport but do not include the 26.3
Velocity/Bungee central proxy modules.

TCP retransmission and ordering may increase latency on lossy connections. The
transport retains upstream encryption; this is not TLS and introduces no claim of
stronger security than upstream.

## Verification

`scripts/test-tcp.ps1` and `scripts/test-tcp.sh` compile production transport sources
directly with JDK 25 and run real loopback socket regressions without game assets.
Platform builds use the upstream Gradle wrapper. A game smoke test should use two
clients with this fork, confirm authentication, distance/group/whisper audio, mute,
logout/rejoin, and verify recovery after interrupting the voice TCP connection.
