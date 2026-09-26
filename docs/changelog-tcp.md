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
