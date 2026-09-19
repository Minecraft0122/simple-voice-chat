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
The fork does not support the upstream UDP voice proxies or WebRTC transport. For a
For Minecraft proxy deployment, route the voice TCP port directly to the appropriate game
server, or use an ordinary TCP forwarding service. The Velocity/Bungee proxy modules
inherited on the 26.3 branch remain UDP and are excluded from the supported build; the
Bukkit server plugin is included and uses the same TCP transport as the mod platforms.

TCP retransmission and ordering may increase latency on lossy connections. The
transport retains upstream encryption; this is not TLS and introduces no claim of
stronger security than upstream.

## Verification

`scripts/test-tcp.ps1` and `scripts/test-tcp.sh` compile production transport sources
directly with JDK 25 and run real loopback socket regressions without game assets.
Platform builds use the upstream Gradle wrapper. A game smoke test should use two
clients with this fork, confirm authentication, distance/group/whisper audio, mute,
logout/rejoin, and verify recovery after interrupting the voice TCP connection.
