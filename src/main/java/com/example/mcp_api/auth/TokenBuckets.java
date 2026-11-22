package com.example.mcp_api.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Token buckets for credential verification (MSG-2807): one bucket per client key and one global bucket.
 * A take succeeds only if both buckets have a token; otherwise neither is charged and the caller learns
 * how long to wait. Buckets refill continuously. The per-client map is bounded (least recently used keys
 * are dropped, which only ever gives a dropped client a full bucket again; the global bucket still holds).
 */
final class TokenBuckets {

    static final int MAX_CLIENTS = 10_000;

    private static final class Bucket {
        double tokens;
        long updatedMs;

        Bucket(double tokens, long nowMs) {
            this.tokens = tokens;
            this.updatedMs = nowMs;
        }

        void refill(long nowMs, int capacity, double perMs) {
            if (nowMs > updatedMs) {
                tokens = Math.min(capacity, tokens + (nowMs - updatedMs) * perMs);
                updatedMs = nowMs;
            }
        }

        /** Milliseconds until one token is available (0 if one is available now). */
        long waitMs(double perMs) {
            return tokens >= 1 ? 0 : (long) Math.ceil((1 - tokens) / perMs);
        }
    }

    private final int clientCapacity;
    private final double clientPerMs;
    private final int globalCapacity;
    private final double globalPerMs;
    private final LongSupplier clock;
    private final Bucket global;
    private final Map<String, Bucket> clients = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_CLIENTS;
        }
    };

    TokenBuckets(int clientCapacity, int clientRefillPerMinute, int globalCapacity, int globalRefillPerMinute,
                 LongSupplier clock) {
        if (clientCapacity < 1 || clientRefillPerMinute < 1 || globalCapacity < 1 || globalRefillPerMinute < 1) {
            throw new IllegalArgumentException("Rate limit capacities and refill rates must be at least 1");
        }
        this.clientCapacity = clientCapacity;
        this.clientPerMs = clientRefillPerMinute / 60_000.0;
        this.globalCapacity = globalCapacity;
        this.globalPerMs = globalRefillPerMinute / 60_000.0;
        this.clock = clock;
        this.global = new Bucket(globalCapacity, clock.getAsLong());
    }

    /** Takes one token for {@code client} and one global token. Returns 0 on success, else ms to wait. */
    synchronized long tryAcquire(String client) {
        long now = clock.getAsLong();
        Bucket mine = clients.computeIfAbsent(client, k -> new Bucket(clientCapacity, now));
        mine.refill(now, clientCapacity, clientPerMs);
        global.refill(now, globalCapacity, globalPerMs);
        long wait = Math.max(mine.waitMs(clientPerMs), global.waitMs(globalPerMs));
        if (wait > 0) return wait;
        mine.tokens -= 1;
        global.tokens -= 1;
        return 0;
    }
}
