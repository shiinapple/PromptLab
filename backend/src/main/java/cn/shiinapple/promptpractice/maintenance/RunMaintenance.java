package cn.shiinapple.promptpractice.maintenance;

import cn.shiinapple.promptpractice.common.SqliteWrites;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
public class RunMaintenance {
    private static final int BATCH_SIZE = 100;
    private final JdbcClient jdbc;
    private final SqliteWrites writes;
    private final RunRetention retention;

    RunMaintenance(JdbcClient jdbc, PlatformTransactionManager transactions, RunRetention retention) {
        this.jdbc = jdbc;
        this.writes = new SqliteWrites(transactions, jdbc);
        this.retention = retention;
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
                        WHERE r.status <> 'PENDING'
                          AND ((r.mode = 'D0' AND r.created_at <= :d0) OR (r.mode = 'DIRECT' AND r.created_at <= :direct))
                          AND NOT EXISTS (SELECT 1 FROM submissions a WHERE a.run_id = r.id AND a.user_id = r.user_id)
                        ORDER BY r.created_at, r.id LIMIT :batch
                    )
                    """).param("d0", now - retention.d0().toMillis())
                    .param("direct", now - retention.direct().toMillis()).param("batch", BATCH_SIZE).update();
        });
    }

    public record Result(int expired, int deletedSnapshots) {}
}
