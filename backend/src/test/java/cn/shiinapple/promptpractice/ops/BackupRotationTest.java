package cn.shiinapple.promptpractice.ops;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.sqlite.SQLiteDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
@EnabledOnOs({OS.MAC, OS.LINUX})
class BackupRotationTest {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void databasePath(DynamicPropertyRegistry registry) {
        registry.add("prompt-practice.database.path", () -> directory.resolve("live.sqlite3").toString());
    }

    @Autowired BackupFiles backups;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource source;

    @Test
    void retainsSevenVerifiedFilesAndDoesNotPruneByAgeByDefault() throws Exception {
        Path destination = directory.resolve("backups");
        jdbc.sql("INSERT INTO users(id, created_at) VALUES ('backup-fixture', 123)").update();
        Path first;
        try (var writer = source.getConnection(); var statement = writer.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                statement.execute("UPDATE users SET created_at = 456 WHERE id = 'backup-fixture'");
                first = backups.backup(destination);
            } finally {
                statement.execute("ROLLBACK");
            }
        }

        Path unrelated = Files.writeString(destination.resolve("preserve.sqlite3"), "unrelated");
        Path firstRestore = directory.resolve("first-restore");
        var firstCopy = BackupFiles.restore(first, firstRestore);
        var firstDb = new SQLiteDataSource();
        firstDb.setUrl("jdbc:sqlite:" + firstCopy.database());
        assertEquals(123L, JdbcClient.create(firstDb).sql("SELECT created_at FROM users WHERE id = 'backup-fixture'")
                .query(Long.class).single(), "Active write must stay outside the backup");
        Files.setLastModifiedTime(first, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Path second = backups.backup(destination);
        assertTrue(Files.exists(first), "Default retention is by count");

        for (int i = 0; i < 7; i++) backups.backup(destination);
        List<Path> kept;
        try (var entries = Files.list(destination)) {
            kept = entries.filter(file -> file.getFileName().toString().startsWith("prompt-practice-"))
                    .sorted().toList();
        }
        assertEquals(7, kept.size());
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertEquals("unrelated", Files.readString(unrelated));

        Path restored = directory.resolve("restored");
        var result = BackupFiles.restore(kept.getLast(), restored);
        assertEquals(1L, result.rowCounts().get("users"));
        var sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + result.database());
        var restoredDb = JdbcClient.create(sqlite);
        assertEquals("ok", restoredDb.sql("PRAGMA integrity_check").query(String.class).single());
        assertTrue(restoredDb.sql("PRAGMA foreign_key_check").query(String.class).list().isEmpty());
        assertEquals(123L, restoredDb.sql("SELECT created_at FROM users WHERE id = 'backup-fixture'")
                .query(Long.class).single());
    }

    @Test
    void rejectsAnOverlappingBackupAndRestoresOnlyAStandaloneFile() throws Exception {
        Path destination = directory.resolve("busy-backup");
        Path saved = backups.backup(destination);
        try (var channel = FileChannel.open(destination.resolve(".backup.lock"), StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertThrows(IOException.class, () -> backups.backup(destination));
        }
        Path sidecar = saved.resolveSibling(saved.getFileName() + "-journal");
        Files.createFile(sidecar);
        Path restore = directory.resolve("reject-restore");
        assertThrows(IOException.class, () -> BackupFiles.restore(saved, restore));
        assertFalse(Files.exists(restore));
    }
}
