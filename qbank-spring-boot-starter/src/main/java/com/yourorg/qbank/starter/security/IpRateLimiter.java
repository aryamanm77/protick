package com.yourorg.qbank.starter.security;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed/sliding window rate limiter guarding login endpoints against offline LAN brute-force bursts.
 */
public class IpRateLimiter {

    private final int maxAttemptsPerWindow;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public IpRateLimiter(int maxAttemptsPerWindow, long windowSeconds) {
        this.maxAttemptsPerWindow = Math.max(1, maxAttemptsPerWindow);
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    public boolean tryConsume(String key, Instant now) {
        long nowSec = now.getEpochSecond();
        long cutoff = nowSec - windowSeconds;
        Deque<Long> q = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= cutoff) {
                q.pollFirst();
            }
            if (q.size() >= maxAttemptsPerWindow) {
                return false;
            }
            q.addLast(nowSec);
            return true;
        }
    }
}
