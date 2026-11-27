package cn.shiinapple.promptpractice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void migratesAnEmptyDatabaseAndRestartsWithTheSameBaseline() {
        Path file = directory.resolve("data with spaces").resolve("baseline.sqlite3");
        try (var context = start(file)) {
            assertTrue(Files.isRegularFile(file));
            assertEquals("5", version(context));
        }
        try (var context = start(file)) {
            assertEquals("5", version(context));
            assertEquals(1L, context.getBean(JdbcClient.class)
                    .sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1'")
                    .query(Long.class).single());
        }
    }

    @Test
    void upgradesTheBaselineWithoutLosingExistingData() {
        Path file = directory.resolve("upgrade.sqlite3");
        try (var context = start(file, "--spring.flyway.target=2")) {
            var jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("CREATE TABLE fixture (id INTEGER PRIMARY KEY, value TEXT NOT NULL)").update();
            jdbc.sql("INSERT INTO fixture VALUES (1, 'existing row')").update();
            jdbc.sql("INSERT INTO users(id, created_at) VALUES ('existing-local', 0)").update();
            assertEquals("2", version(context));
        }
        try (var context = start(file, "--spring.flyway.locations=classpath:db/migration,classpath:db/upgrade")) {
            var jdbc = context.getBean(JdbcClient.class);
            assertEquals("1000", version(context));
            assertEquals("DISABLED", jdbc.sql("SELECT state FROM users WHERE id = 'existing-local'")
                    .query(String.class).single());
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM runs").query(Long.class).single());
            assertEquals("existing row", jdbc.sql("SELECT value FROM fixture WHERE id = 1")
                    .query(String.class).single());
            assertEquals(0, jdbc.sql("SELECT revision FROM fixture WHERE id = 1").query(Integer.class).single());
        }
    }

    @Test
    void configuresEveryPhysicalConnectionAndEnforcesForeignKeys() throws SQLException {
        try (var context = start(directory.resolve("pool.sqlite3"))) {
            var dataSource = context.getBean(DataSource.class);
            var connections = new ArrayList<Connection>();
            try {
                for (int index = 0; index < 4; index++) {
                    connections.add(dataSource.getConnection());
                }
                for (Connection connection : connections) {
                    assertEquals("wal", pragma(connection, "journal_mode"));
                    assertEquals("1", pragma(connection, "foreign_keys"));
                    assertEquals("1000", pragma(connection, "busy_timeout"));
                }
            } finally {
                for (Connection connection : connections) {
                    connection.close();
                }
            }
            var jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("CREATE TABLE parent (id INTEGER PRIMARY KEY)").update();
            jdbc.sql("CREATE TABLE child (parent_id INTEGER REFERENCES parent(id))").update();
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                var error = assertThrows(SQLiteException.class, () ->
                        statement.executeUpdate("INSERT INTO child VALUES (42)"));
                assertEquals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY, error.getResultCode());
            }
        }
    }

    @Test
    void rollsBackWritesThroughSpringTransactions() {
        try (var context = start(directory.resolve("rollback.sqlite3"))) {
            var jdbc = context.getBean(JdbcClient.class);
            jdbc.sql("CREATE TABLE fixture (id INTEGER PRIMARY KEY)").update();
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                jdbc.sql("INSERT INTO fixture VALUES (1)").update();
                jdbc.sql("INSERT INTO fixture VALUES (2)").update();
                assertEquals(2L, jdbc.sql("SELECT count(*) FROM fixture").query(Long.class).single());
                throw new IllegalStateException("abort fixture transaction");
            }));
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM fixture").query(Long.class).single());
        }
    }

    private ConfigurableApplicationContext start(Path file, String... options) {
        var arguments = new ArrayList<String>();
        arguments.add("--prompt-practice.database.path=" + file);
        arguments.add("--spring.main.banner-mode=off");
        arguments.addAll(List.of(options));
        return new SpringApplicationBuilder(PromptPracticeApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    private String version(ConfigurableApplicationContext context) {
        return context.getBean(Flyway.class).info().current().getVersion().getVersion();
    }

    private String pragma(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA " + name)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
