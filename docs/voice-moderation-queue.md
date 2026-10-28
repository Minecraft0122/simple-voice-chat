# Voice moderation queue

The server can keep short Opus voice segments on disk and expose them to a local moderation process without opening a new network port. Enable `voice_moderation_cache_enabled=true` only after informing players and configuring access to the server's data directory. The default retention is 12 hours.

The files are stored below the voice chat configuration directory:

```text
moderation/
  segments/<player-uuid>-<unix-minute>.vcm
  queue/priority/<task-uuid>.json
  queue/periodic/<task-uuid>.json
```

`priority` is used by `/voicechat report <player> [reason]` and is written immediately. `periodic` is filled once per minute for completed segments that have not been queued before. A moderation process should watch `queue/priority` first, then `queue/periodic`, atomically move a task into its own `processing` directory, inspect it, and finally move it to `processed` or `rejected`.

Each task is JSON and contains `task_id`, `kind`, `player_uuid`, optional `reporter_uuid`, `reason`, `created_at_millis`, and a `segments` array. Segment names are relative to `moderation/segments`.

Each `.vcm` file is an append-only sequence of records in network byte order:

```text
int64 timestamp_millis
int64 opus_sequence
byte  whispering (0 or 1)
int32 opus_length
byte[opus_length] opus_data
```

The `opus_data` field is the original encoded microphone frame. The external program can decode each frame with the same Opus settings as the client, run speech-to-text or a policy classifier, and record its result beside the task. The server does not execute arbitrary commands from configuration; the queue is the hand-off boundary to the separately managed local program.

Files older than `voice_moderation_cache_hours` are removed. When `voice_moderation_cache_max_mb` is exceeded, the oldest segment files are removed first. Keep the consumer on the same host and account as the server, and remove or protect the queue directories according to the server's privacy policy.
