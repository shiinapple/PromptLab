package cn.shiinapple.promptpractice.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration(proxyBeanMethods = false)
class SqliteConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(@Value("${prompt-practice.database.path}") String databasePath)
            throws IOException, SQLException {
        Path file = Path.of(databasePath).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());

        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(1000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        var sqlite = new SQLiteDataSource(config);
        sqlite.setUrl("jdbc:sqlite:" + file.toUri().toASCIIString());

        // WAL persists on the file. Enable it before Flyway and the pool open connections.
        try (var connection = sqlite.getConnection();
             var statement = connection.createStatement();
             var mode = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) {
                throw new SQLException("SQLite did not enter WAL mode");
            }
        }

        var pool = new HikariConfig();
        pool.setDataSource(sqlite);
        pool.setMinimumIdle(1);
        pool.setMaximumPoolSize(4);
        pool.setConnectionTimeout(5000);
        return new HikariDataSource(pool);
    }
}
