package cn.shiinapple.promptpractice.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import cn.shiinapple.promptpractice.common.SqliteWrites;
import cn.shiinapple.promptpractice.practice.PracticeError;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class AccountDeletion {
    private static final long GRACE_PERIOD_MS = Duration.ofDays(3).toMillis();
    private static final int BATCH_SIZE = 100;
    private final JdbcClient jdbc;
    private final SqliteWrites writes;
    private final Accounts accounts;
    private final Clock clock;

    @Autowired
    AccountDeletion(JdbcClient jdbc, PlatformTransactionManager transactions, Accounts accounts) {
        this(jdbc, transactions, accounts, Clock.systemUTC());
    }

    AccountDeletion(JdbcClient jdbc, PlatformTransactionManager transactions, Accounts accounts, Clock clock) {
        this.jdbc = jdbc;
        this.writes = new SqliteWrites(transactions, jdbc);
        this.accounts = accounts;
        this.clock = clock;
    }

    DeletionStatus status(String userId) {
        return pending(userId).orElse(new DeletionStatus("NONE", null, null));
    }

    RequestResult request(String userId) {
        return writes.execute(() -> {
            requireActive(userId);
            long now = clock.millis();
            int inserted = jdbc.sql("""
                    INSERT INTO account_deletion_requests (user_id, requested_at, effective_at)
                    VALUES (?, ?, ?) ON CONFLICT (user_id) DO NOTHING
                    """).params(userId, now, now + GRACE_PERIOD_MS).update();
            return new RequestResult(inserted == 1, pending(userId).orElseThrow());
        });
    }

    void withdraw(String userId) {
        writes.execute(() -> {
            requireActive(userId);
            int deleted = jdbc.sql("""
                    DELETE FROM account_deletion_requests WHERE user_id = ? AND effective_at > ?
                    """).params(userId, clock.millis()).update();
            if (deleted == 0) throw new PracticeError(409, "NO_WITHDRAWABLE_DELETION_REQUEST");
            return deleted;
        });
    }

    public int deleteDue() {
        return writes.execute(() -> jdbc.sql("""
                DELETE FROM users WHERE id IN (
                    SELECT user_id FROM account_deletion_requests WHERE effective_at <= :now
                    ORDER BY effective_at, user_id LIMIT :batch
                )
                """).param("now", clock.millis()).param("batch", BATCH_SIZE).update());
    }

    private void requireActive(String userId) {
        if (accounts.active(userId).isEmpty()) throw new PracticeError(401, "AUTH_REQUIRED");
    }

    private Optional<DeletionStatus> pending(String userId) {
        return jdbc.sql("SELECT requested_at, effective_at FROM account_deletion_requests WHERE user_id = ?")
                .param(userId).query((row, index) -> new DeletionStatus("PENDING",
                        Instant.ofEpochMilli(row.getLong("requested_at")).toString(),
                        Instant.ofEpochMilli(row.getLong("effective_at")).toString())).optional();
    }

    record RequestResult(boolean created, DeletionStatus status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeletionStatus(String status, String requestedAt, String effectiveAt) {}
}
