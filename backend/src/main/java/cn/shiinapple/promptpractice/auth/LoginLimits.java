package cn.shiinapple.promptpractice.auth;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.stereotype.Component;

@Component
class LoginLimits {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private static final int MAX_TRACKED = 4096;
    private final Map<String, Attempts> sources = new HashMap<>();
    private final Map<String, Attempts> emails = new HashMap<>();
    private final Semaphore workers = new Semaphore(2);

    String authenticate(Accounts accounts, String source, String email, String password) {
        if (!workers.tryAcquire()) throw new PracticeError(429, "RATE_LIMITED");
        try {
            reserve(source, email);
            return accounts.authenticate(email, password)
                    .orElseThrow(() -> new PracticeError(401, "INVALID_CREDENTIALS"));
        } finally {
            workers.release();
        }
    }

    private synchronized void reserve(String source, String email) {
        long now = System.nanoTime();
        count(sources, source, 60, now);
        count(emails, email, 8, now);
    }

    private static void count(Map<String, Attempts> entries, String key, int limit, long now) {
        var attempts = entries.get(key);
        if (attempts == null || now - attempts.startedAt >= WINDOW) {
            if (attempts == null && entries.size() >= MAX_TRACKED) {
                entries.values().removeIf(value -> now - value.startedAt >= WINDOW);
                if (entries.size() >= MAX_TRACKED) throw new PracticeError(429, "RATE_LIMITED");
            }
            attempts = new Attempts(now);
            entries.put(key, attempts);
        }
        if (attempts.count >= limit) throw new PracticeError(429, "RATE_LIMITED");
        attempts.count++;
    }

    private static final class Attempts {
        final long startedAt;
        int count;

        Attempts(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
