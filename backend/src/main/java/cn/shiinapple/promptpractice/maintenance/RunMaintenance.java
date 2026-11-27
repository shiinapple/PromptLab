package cn.shiinapple.promptpractice.maintenance;

import java.time.Duration;

import cn.shiinapple.promptpractice.common.SqliteWrites;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
public class RunMaintenance {
    private static final int BATCH_SIZE = 100;
    private static final long RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    private final JdbcClient jdbc;
    private final SqliteWrites writes;

    RunMaintenance(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.writes = new SqliteWrites(transactions, jdbc);
    }

    public Result sweep() {
        return new Result(expire(), clean());
    }

    int expire() {
        return writes.execute(() -> jdbc.sql("""
                UPDATE runs SET status = 'EXPIRED', finished_at = expires_at
                WHERE status = 'PENDING' AND expires_at <= :now AND id IN (
                    SELECT id FROM runs WHERE status = 'PENDING' AND expires_at <= :now
                    ORDER BY expires_at, id LIMIT :batch
                )
                """).param("now", System.currentTimeMillis()).param("batch", BATCH_SIZE).update());
    }

    int clean() {
        return writes.execute(() -> {
            long now = System.currentTimeMillis();
            return jdbc.sql("""
                    DELETE FROM prompt_snapshots
                    WHERE id IN (
                        SELECT r.snapshot_id FROM runs r
                        WHERE r.finished_at <= :cutoff
                          AND NOT EXISTS (SELECT 1 FROM submissions a WHERE a.run_id = r.id AND a.user_id = r.user_id)
                        ORDER BY r.finished_at, r.id LIMIT :batch
                    )
                    """).param("cutoff", now - RETENTION_MILLIS).param("batch", BATCH_SIZE).update();
        });
    }

    public record Result(int expired, int deletedSnapshots) {}
}
