package cn.shiinapple.promptpractice.pocsqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    @TempDir
    Path directory;

    static Flyway migrations(DataSource source, String target, String... locations) {
        return Flyway.configure().dataSource(source).target(target).locations(locations).load();
    }

    static void migrate(DataSource source) {
        migrations(source, "latest", "classpath:db/migration").migrate();
    }

    static long countRuns(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM runs").query(Long.class).single();
    }

    @Test
    void emptyDatabaseMigratesAndOpeningAgainDoesNotReapplyMigrations() throws SQLException {
        var file = directory.resolve("fresh.sqlite");
        try (var source = SqlitePoc.openPool(file, SqlitePoc.BUSY_TIMEOUT_MS)) {
            var flyway = migrations(source, "latest", "classpath:db/migration");
            assertEquals(2, flyway.migrate().migrationsExecuted);
            assertEquals("2", flyway.info().current().getVersion().getVersion());
        }
        try (var source = SqlitePoc.openPool(file, SqlitePoc.BUSY_TIMEOUT_MS)) {
            var flyway = migrations(source, "latest", "classpath:db/migration");
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertDoesNotThrow(flyway::validate);
            assertEquals(0, countRuns(JdbcClient.create(source)));
        }
    }

    @Test
    void upgradeFromV1PreservesRowsAndAppliesDefaultRevision() throws SQLException {
        var file = directory.resolve("upgrade.sqlite");
        try (var source = SqlitePoc.openPool(file, SqlitePoc.BUSY_TIMEOUT_MS)) {
            assertEquals(1, migrations(source, "1", "classpath:db/migration").migrate().migrationsExecuted);
            var jdbc = JdbcClient.create(source);
            jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
            jdbc.sql("INSERT INTO runs VALUES (28, 1, 'DONE', 'retained')").update();
        }
        try (var source = SqlitePoc.openPool(file, SqlitePoc.BUSY_TIMEOUT_MS)) {
            assertEquals(1, migrations(source, "latest", "classpath:db/migration").migrate().migrationsExecuted);
            var jdbc = JdbcClient.create(source);
            assertEquals("retained", jdbc.sql("SELECT result FROM runs WHERE id=28").query(String.class).single());
            assertEquals(0, jdbc.sql("SELECT revision FROM runs WHERE id=28").query(Integer.class).single());
        }
    }

    @Test
    void migrationFailureRollsBackDdlAndKeepsPriorVersion() throws SQLException {
        try (var source = SqlitePoc.openPool(directory.resolve("broken.sqlite"), SqlitePoc.BUSY_TIMEOUT_MS)) {
            migrate(source);
            var jdbc = JdbcClient.create(source);
            jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
            jdbc.sql("INSERT INTO runs (id, user_id, state) VALUES (1, 1, 'PENDING')").update();
            var broken = migrations(source, "latest", "classpath:db/migration", "classpath:db/broken");
            var failure = assertThrows(FlywayException.class, broken::migrate);
            assertTrue(failure.getMessage().contains("missing_fixture_table"), failure::getMessage);
            assertEquals(0, jdbc.sql("SELECT count(*) FROM sqlite_master WHERE name='should_rollback'")
                    .query(Integer.class).single());
            assertEquals(0, jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version='3'")
                    .query(Integer.class).single());
            assertEquals("2", broken.info().current().getVersion().getVersion());
            assertEquals(1, countRuns(jdbc));
        }
    }

    @Test
    void everyPooledConnectionHasForeignKeysAndBusyTimeout() throws SQLException {
        try (var source = SqlitePoc.openPool(directory.resolve("pragmas.sqlite"), SqlitePoc.BUSY_TIMEOUT_MS);
             var first = source.getConnection();
             var second = source.getConnection();
             var third = source.getConnection();
             var fourth = source.getConnection()) {
            for (var connection : List.of(first, second, third, fourth)) {
                assertEquals("wal", pragma(connection, "journal_mode"));
                assertEquals("1", pragma(connection, "foreign_keys"));
                assertEquals(Integer.toString(SqlitePoc.BUSY_TIMEOUT_MS), pragma(connection, "busy_timeout"));
            }
        }
    }

    private static String pragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement(); var value = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(value.next());
            return value.getString(1);
        }
    }

    @Test
    void jdbcTransactionRollsBackAndRejectsOrphanRun() throws SQLException {
        try (var source = SqlitePoc.openPool(directory.resolve("transaction.sqlite"), SqlitePoc.BUSY_TIMEOUT_MS)) {
            migrate(source);
            var jdbc = JdbcClient.create(source);
            var tx = new TransactionTemplate(new JdbcTransactionManager(source));
            assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(ignored -> {
                jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
                jdbc.sql("INSERT INTO runs (id, user_id, state) VALUES (1, 1, 'PENDING')").update();
                throw new IllegalStateException("controlled rollback");
            }));
            assertEquals(0, countRuns(jdbc));
            assertEquals(0, jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single());
            var error = assertThrows(DataAccessException.class, () -> jdbc
                    .sql("INSERT INTO runs (id, user_id, state) VALUES (1, 99, 'PENDING')").update());
            assertTrue(error.getMostSpecificCause().getMessage().contains("SQLITE_CONSTRAINT_FOREIGNKEY"));
        }
    }

    @Test
    void walReaderSeesCommittedStateAndSecondWriterHitsBusyTimeout() throws SQLException {
        try (var source = SqlitePoc.openPool(directory.resolve("busy.sqlite"), 250)) {
            migrate(source);
            var jdbc = JdbcClient.create(source);
            jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
            try (var writer = source.getConnection(); var statement = writer.createStatement()) {
                writer.setAutoCommit(false);
                try {
                    statement.executeUpdate("INSERT INTO runs (id, user_id, state) VALUES (1, 1, 'PENDING')");
                    assertEquals(0, countRuns(jdbc));
                    long started = System.nanoTime();
                    var error = assertThrows(DataAccessException.class, () -> jdbc
                            .sql("INSERT INTO runs (id, user_id, state) VALUES (2, 1, 'PENDING')").update());
                    long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    assertEquals(5, ((SQLException) error.getMostSpecificCause()).getErrorCode());
                    assertTrue(millis >= 180, "Expected the busy handler to wait about 250 ms, got " + millis);
                    System.out.printf("POC_BUSY expected_sqlite_code=5 timeout_ms=250 observed_ms=%d%n", millis);
                    writer.commit();
                } finally {
                    writer.rollback();
                }
            }
            assertEquals(1, countRuns(jdbc));
            assertEquals(1, jdbc.sql("INSERT INTO runs (id, user_id, state) VALUES (2, 1, 'PENDING')").update());
        }
    }

    @Test
    void vacuumIntoRestoresSnapshotFromOpenWalDatabase() throws Exception {
        var sourcePath = directory.resolve("source.sqlite");
        var backup = directory.resolve("backup copy.sqlite");
        try (var source = SqlitePoc.openPool(sourcePath, SqlitePoc.BUSY_TIMEOUT_MS)) {
            migrate(source);
            var jdbc = JdbcClient.create(source);
            jdbc.sql("INSERT INTO users VALUES (1, 'fixture')").update();
            jdbc.sql("INSERT INTO runs VALUES (1, 1, 'DONE', 'snapshot-value', 2)").update();
            assertTrue(Files.size(directory.resolve("source.sqlite-wal")) > 0);
            jdbc.sql("VACUUM INTO ?").param(backup.toString()).update();
            jdbc.sql("INSERT INTO runs VALUES (2, 1, 'DONE', 'after-backup', 3)").update();
            assertEquals(2, countRuns(jdbc));
        }
        var restored = Files.copy(backup, directory.resolve("restored.sqlite"));
        try (var source = SqlitePoc.openPool(restored, SqlitePoc.BUSY_TIMEOUT_MS)) {
            var jdbc = JdbcClient.create(source);
            assertEquals("ok", jdbc.sql("PRAGMA integrity_check").query(String.class).single());
            assertTrue(jdbc.sql("PRAGMA foreign_key_check").query().listOfRows().isEmpty());
            assertEquals(1, countRuns(jdbc));
            assertEquals("snapshot-value", jdbc.sql("SELECT result FROM runs WHERE id=1").query(String.class).single());
            assertEquals(2, jdbc.sql("SELECT revision FROM runs WHERE id=1").query(Integer.class).single());
            assertEquals(0, migrations(source, "latest", "classpath:db/migration").migrate().migrationsExecuted);
        }
    }
}
