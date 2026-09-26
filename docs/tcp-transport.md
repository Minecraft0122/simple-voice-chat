# TCP voice transport

This fork targets Minecraft 26.1, 26.2 and 26.3 in separate `tcp/26.x` branches.
It retains the upstream license, codecs, encryption, application packets, game-channel
initialization, group management and public API names. It is not wire compatible with
the upstream UDP transport. Compatibility version `1021` distinguishes it during the
Minecraft-channel handshake; install this fork on both ends.

## Wire format

Each TCP connection carries a sequence of frames. A frame is a **4-byte big-endian
signed integer length**, followed by exactly that many bytes of the original encrypted
voice packet. Legal lengths are 1 through 4096 inclusive. The existing voice packet
payload limits still apply after decryption. The decoder rejects invalid lengths
before allocating the payload and closes truncated or timed-out streams. Multiple
frames in one TCP read and frames split across TCP reads are both valid.

The challenge/response authentication exchanges remain inside these frames. A connection must
authenticate using the player's secret delivered through Minecraft networking before
its audio can be routed. `RawUdpPacket` is a historical API name for the packet envelope;
it does not indicate UDP traffic in this fork. Custom socket implementations must use
the same TCP framing on both ends.

## Network and lifecycle

The voice listener uses a separate TCP port, default `24454`. `voice_host` advertises
an external address/port in the same way as upstream. Minecraft's TCP listener cannot
share its port with this listener. Legacy `port=-1` maps to `24454`; explicit selection
of the game port or port 0 is rejected with an actionable error. Integrated servers use
the configured fixed voice port and retain it when the game is opened to LAN.

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

## Central proxy mode (26.3, protocol 1021)

Enable `proxy_mode=true` on each backend using this branch. The proxy owns the public
fixed TCP port (default 24454); `port=0` refuses voice-service startup on both proxy and
backend. Client, backend and proxy must all use this revision. Java 25 is required.
The 26.1/26.2 branches retain their earlier standalone TCP protocol and are not changed
by this revision.

Backends send their player UUID in the secret handshake. The proxy never guesses a
missing mapping from a proxy UUID. Inconsistent or missing uploaded identity disables
voice for the affected player, displays a localized internal-server-error message, and
logs the UUID, backend and reason. Outgoing positional audio uses the backend entity
UUID, so a valid explicit mapping can differ from the proxy UUID.

Routing snapshots are computed on the server thread every 250 ms, with at most one
queued update. Unchanged routes use a 33-byte heartbeat once per second instead of
resending the full recipient lists. Changes send a new snapshot immediately on the next
update. Identity, generation, increasing update sequence, backend connection source and
three-second freshness are checked. Server switches clear credentials and routing state;
stale messages from a previous backend are discarded. Target calculation remains local
to each backend: this is not a cross-server group directory.

The client starts with transmission disabled. It only sends microphone packets after
challenge authentication, connection validation and an available status. Joining a
backend without a responding plugin produces a localized unavailable message after
five seconds. Terminal unavailable/internal-error states close the old voice connection,
clear credentials and cancel retries. Spectator/permission/disabled states stop the mic
while retaining control keepalives, allowing recovery when eligibility changes.
`allow_spectator_voice=false` disables both sending and receiving by default. Enabling
spectator voice uses the backend processing path so possession and location behavior
are preserved.

The proxy reports validated connection state and a per-connection session UUID to the
backend, refreshing it once per second. Backends only emit connected events after this
confirmation; absent confirmations expire the state. Control messages bind the backend
generation and the connection session. Client-originated copies of proxy control, audio,
routing and availability messages are intercepted and dropped by both proxy adapters.
Only messages from the player's current backend connection are accepted.

Normal voice is decrypted, routed and re-encrypted entirely at the proxy. When a backend
registers microphone, sound-packet, voice-distance events or player audio listeners,
that backend automatically receives microphone payloads through the trusted control
channel. Its existing plugin processing chain runs, and resulting sound packets return
to the proxy for session encryption. API audio senders/channels use the same return path; API ping replies are forwarded
back to the originating backend session.
This deliberately spends backend bandwidth only where an addon needs the audio; those
addons cannot inspect raw microphone data without receiving it. Extremely large target
lists also use this path to stay below Bukkit's plugin-message payload limit.

## Authentication and encryption

Audio remains Opus encoded and AES-128-GCM encrypted (12-byte random IV, 128-bit tag).
Protocol 1021 adds an encrypted server challenge (packet 0x0B) with 32 random bytes per
TCP connection. The client returns a 32-byte proof (0x0C). HKDF-SHA256 extracts with the
challenge as salt and the Minecraft-delivered player secret as input. Separate expand
labels for authentication, client traffic and server traffic also bind the player UUID
and challenge. Authentication proofs are accepted only on their originating connection,
expire after ten seconds, and cannot replace an existing session until verified.
Authentication acknowledgement and all subsequent traffic use the directional session
keys. A repeated handshake cannot reset the microphone sequence window.

The microphone counter belongs to the connection, so replacing an audio device preserves
its sequence. A fresh TCP connection receives fresh keys and its own counter. Availability
(packet 0x0D) gates client microphone transmission in addition to server-side checks.

The proxy is a trusted plaintext boundary. This is not TLS or end-to-end encryption and
HKDF with a shared secret does not provide forward secrecy. The Minecraft identity/key
channel and the backend network must be trusted/protected; backend ports must not be
publicly bypassable when using proxy-owned control channels. See
[crypto-evaluation.md](crypto-evaluation.md) for the algorithm comparison.

TCP retransmission and ordering can increase latency on lossy connections.

## Verification

`scripts/test-tcp.ps1` and `scripts/test-tcp.sh` compile production transport sources
directly with JDK 25 and run real loopback socket regressions without game assets.
Platform builds use the upstream Gradle wrapper. A game smoke test should use two
clients with this fork, confirm authentication, distance/group/whisper audio, mute,
logout/rejoin, and verify recovery after interrupting the voice TCP connection.

`test-runtime.ps1` / `test-runtime.sh` compile the production proxy endpoint, sniffer and
codec and use real loopback sockets. `python tests/client/run.py` compiles the production
client lifecycle/connection classes against game, audio and socket doubles. Neither
replaces a Minecraft game smoke test. CI also builds all supported platforms and includes
Velocity and BungeeCord JARs in its artifacts.
