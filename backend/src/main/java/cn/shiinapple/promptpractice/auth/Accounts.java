package cn.shiinapple.promptpractice.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class Accounts {
    private static final Pattern EMAIL = Pattern.compile("[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+");
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());

    Accounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public String create(String email, CharSequence password) {
        String normalized = normalizeEmail(email);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > 254 || !EMAIL.matcher(normalized).matches()
                || password.length() < 12 || password.length() > 256) {
            throw new IllegalArgumentException("Supply a valid email and a password of 12 to 256 characters");
        }
        String id = UUID.randomUUID().toString();
        if (jdbc.sql("""
                INSERT INTO users(id, created_at, email_normalized, password_hash, state)
                VALUES (?, ?, ?, ?, 'ACTIVE') ON CONFLICT(email_normalized) DO NOTHING
                """).params(id, Instant.now().toEpochMilli(), normalized, passwords.encode(password)).update() == 0) {
            throw new PracticeError(409, "EMAIL_ALREADY_EXISTS");
        }
        return id;
    }

    Optional<String> authenticate(String normalized, String password) {
        var account = jdbc.sql("""
                SELECT id, password_hash FROM users WHERE email_normalized = ? AND state = 'ACTIVE'
                """).param(normalized).query((rs, row) -> new Credential(rs.getString(1), rs.getString(2))).optional();
        String hash = account.map(Credential::hash).orElse(dummyHash);
        return passwords.matches(password, hash) ? account.map(Credential::id) : Optional.empty();
    }

    Optional<Profile> active(String id) {
        return jdbc.sql("SELECT id, email_normalized FROM users WHERE id = ? AND state = 'ACTIVE'")
                .param(id).query((rs, row) -> new Profile(rs.getString(1), rs.getString(2))).optional();
    }

    Set<String> activeIds() {
        return jdbc.sql("SELECT id FROM users WHERE state = 'ACTIVE'").query(String.class).set();
    }

    public void disable(String email) {
        int updated = jdbc.sql("UPDATE users SET state = 'DISABLED' WHERE email_normalized = ?")
                .param(normalizeEmail(email)).update();
        if (updated == 0) throw new PracticeError(404, "RESOURCE_NOT_FOUND");
    }

    public void deleteDisabled(String email) {
        int deleted = jdbc.sql("DELETE FROM users WHERE email_normalized = ? AND state = 'DISABLED'")
                .param(normalizeEmail(email)).update();
        if (deleted == 0) throw new PracticeError(409, "ACCOUNT_NOT_DISABLED_OR_MISSING");
    }

    record Profile(String userId, String email) {
        @Override
        public String toString() {
            return "Profile[redacted]";
        }
    }

    private record Credential(String id, String hash) {
        @Override
        public String toString() {
            return "Credential[redacted]";
        }
    }
}
