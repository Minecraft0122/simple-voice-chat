package de.maxhenkel.voicechat.voice.transport;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Correlates replies with a fresh request over the player's current trusted game connection. */
public final class ProxyConfigSync {
    public static final long TIMEOUT_MS = 5000L;
    private record Request(UUID nonce, long started) {}
    private final ConcurrentHashMap<UUID, Request> requests = new ConcurrentHashMap<>();

    public UUID begin(UUID player, long now) {
        Request next = new Request(UUID.randomUUID(), now);
        return requests.putIfAbsent(player, next) == null ? next.nonce() : null;
    }

    public boolean accept(UUID player, UUID nonce, long now) {
        Request request = requests.get(player);
        return request != null && request.nonce().equals(nonce) && now >= request.started()
                && now - request.started() < TIMEOUT_MS && requests.remove(player, request);
    }

    public boolean isPending(UUID player, UUID nonce, long now) {
        Request request = requests.get(player);
        return request != null && request.nonce().equals(nonce) && now >= request.started()
                && now - request.started() < TIMEOUT_MS;
    }

    public boolean expire(UUID player, UUID nonce) {
        Request request = requests.get(player);
        return request != null && request.nonce().equals(nonce) && requests.remove(player, request);
    }

    public void remove(UUID player) { requests.remove(player); }
}
