package cn.shiinapple.promptpractice.pocsqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

@SpringBootApplication
class SqlitePoc {
    static final int POOL_SIZE = 4;
    static final int BUSY_TIMEOUT_MS = 1000;

    @Bean
    HikariDataSource dataSource(@Value("${poc.db}") Path file) throws SQLException {
        return openPool(file, BUSY_TIMEOUT_MS);
    }

    static HikariDataSource openPool(Path file, int busyTimeoutMillis) throws SQLException {
        var sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(busyTimeoutMillis);
        var sqlite = new SQLiteDataSource(sqliteConfig);
        sqlite.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        // WAL persists on the database file. Set it before making the pool available.
        try (var connection = sqlite.getConnection();
             var statement = connection.createStatement();
             var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            if (!mode.next() || !mode.getString(1).equalsIgnoreCase("wal")) {
                throw new SQLException("The temporary database did not enter WAL mode");
            }
        }
        var hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setMinimumIdle(1);
        hikari.setMaximumPoolSize(POOL_SIZE);
        hikari.setConnectionTimeout(5000);
        return new HikariDataSource(hikari);
    }
}

@RestController
class SqlitePocEndpoint {
    private final JdbcClient jdbc;

    SqlitePocEndpoint(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/poc/status")
    Map<String, Object> status() {
        return Map.of(
                "sqlite", jdbc.sql("SELECT sqlite_version()").query(String.class).single(),
                "runs", jdbc.sql("SELECT count(*) FROM runs").query(Long.class).single()
        );
    }
}
