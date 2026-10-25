# 2.6.24-tcp.3+26.3 — dual transport and automatic proxy mode

- Add transport_mode=tcp|udp|dual, defaulting to tcp for existing installations.
- Automatically enable backend proxy mode from supported game forwarding settings before opening listeners.
- Add proxy_auto_detect (default true), retain a manual override, and exclude OS/TUN proxies from detection.
- Group generated configuration by purpose, add bilingual deployment comments, and preserve existing values.
- Accept upstream protocol 20 through UDP alongside TCP protocol 1021, including central proxies.
- Share routing and addon audio across mixed client types; retain protocol-specific authentication and keys.
- Isolate endpoint identities, preserve upstream microphone sequence resets, and gate TCP-only control messages.
- Add atomic dual binds, bounded per-transport queues, malformed-datagram isolation and mixed-client regressions.
- Document setup, original-client limitations, custom socket requirements and remaining game smoke tests.

# TCP fork changelog

## 2.6.24-tcp.2+26.3 — protocol 1021

- Fix backend compilation, reconnect cancellation and microphone sequence resets after audio reload.
- Authenticate TCP sessions using a fresh challenge and HKDF-SHA256 directional AES-GCM keys.
- Reject missing/conflicting backend UUID mappings, notify the client and log the backend/player context.
- Stop client audio on unavailable backends and permission/spectator restrictions; report missing plugins.
- Forbid configured port 0 and disable spectator voice by default.
- Confirm backend connection state only after proxy authentication; expire stale state.
- Preserve packet-dependent addons via an automatic backend audio relay and support API-generated audio.
- Send routing snapshots on changes and short heartbeats for unchanged routes; validate backend origin.
- Add proxy socket and actual client lifecycle regressions, algorithm evaluation and proxy CI artifacts.

Upgrade clients, backends and proxies together. Existing protocol 1020 clients cannot connect.
