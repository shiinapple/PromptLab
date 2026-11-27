package cn.shiinapple.promptpractice.common;

import java.util.function.Supplier;

import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

public final class SqliteWrites {
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public SqliteWrites(PlatformTransactionManager manager, JdbcClient jdbc) {
        transactions = new TransactionTemplate(manager);
        this.jdbc = jdbc;
    }

    public <T> T execute(Supplier<T> operation) {
        try {
            return transactions.execute(status -> {
                // SQLite reserves the write lock for this statement even with zero matching rows.
                // JDBC can now commit/roll back without Xerial starting another IMMEDIATE transaction.
                jdbc.sql("UPDATE users SET created_at = created_at WHERE 0").update();
                return operation.get();
            });
        } catch (RuntimeException error) {
            if (isBusy(error)) throw new PracticeError(503, "TEMPORARILY_UNAVAILABLE");
            throw error;
        }
    }

    public static boolean isBusy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLiteException sqlite
                    && (sqlite.getResultCode().code & 0xff) == SQLiteErrorCode.SQLITE_BUSY.code) {
                return true;
            }
        }
        return false;
    }
}
