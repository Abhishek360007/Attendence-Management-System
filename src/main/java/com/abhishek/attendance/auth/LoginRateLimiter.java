package com.abhishek.attendance.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: at most {@value #MAX_FAILURES} failed sign-ins per account and per client address
 * within {@link #WINDOW}.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String email, String address) {
        return count(key("u", email)) >= MAX_FAILURES || count(key("a", address)) >= MAX_FAILURES;
    }

    public void recordFailure(String email, String address) {
        add(key("u", email));
        add(key("a", address));
    }

    public void recordSuccess(String email) {
        failures.remove(key("u", email));
    }

    /** Forgets all failures (used by tests). */
    public void reset() {
        failures.clear();
    }

    private static String key(String kind, String value) {
        return kind + ":" + (value == null ? "" : value.toLowerCase(Locale.ROOT));
    }

    private int count(String key) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        synchronized (times) {
            prune(times);
            return times.size();
        }
    }

    private void add(String key) {
        Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            prune(times);
            times.addLast(clock.instant());
        }
        if (failures.size() > 10_000) {
            failures.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    prune(e.getValue());
                    return e.getValue().isEmpty();
                }
            });
        }
    }

    private void prune(Deque<Instant> times) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
    }
}
