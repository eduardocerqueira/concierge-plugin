package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Questions per user per rolling hour, so one person (or a script using their session) cannot spend
 * the whole instance's AI budget. Kept in memory; a restart resets it.
 */
final class RateLimiter {

    static final long WINDOW_MILLIS = 60L * 60 * 1000;

    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    RateLimiter(@NonNull LongSupplier clock) {
        this.clock = clock;
    }

    /** Forgets everyone's history. For tests. */
    void reset() {
        recent.clear();
    }

    /** Records a question and returns true, or returns false if the user is over the limit. */
    boolean tryAcquire(@NonNull String user, int perHour) {
        long now = clock.getAsLong();
        Deque<Long> times = recent.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= perHour) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
