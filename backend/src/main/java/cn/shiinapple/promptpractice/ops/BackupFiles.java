package cn.shiinapple.promptpractice.ops;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Component
class BackupFiles {
    private static final String RESTORED_NAME = "prompt-practice.sqlite3";
    private static final Pattern GENERATED = Pattern.compile("prompt-practice-[0-9]+-[0-9a-f-]{36}\\.sqlite3");
    private static final List<String> TABLES = List.of("users", "problem_versions", "problem_cases", "user_settings",
            "drafts", "prompt_snapshots", "runs", "submissions");
    private final DataSource source;
    private final Duration retention;

    BackupFiles(DataSource source, @Value("${prompt-practice.retention.backup:30d}") Duration retention) {
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("Invalid backup retention");
        this.source = source;
        this.retention = retention;
    }

    Path backup(Path directory) throws SQLException, IOException {
        Path root = directory.toAbsolutePath().normalize();
        createPrivateDirectoryIfMissing(root);
        Path staging = Files.createTempDirectory(root, ".backup-", privateDirectory());
        Path temporary = staging.resolve("snapshot.sqlite3");
        try {
            try (var connection = source.getConnection();
                    var statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, temporary.toString());
                statement.execute();
            }
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            inspect(temporary);
            Path saved = root.resolve("prompt-practice-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".sqlite3");
            Files.createLink(saved, temporary);
            prune(root, Instant.now().minus(retention));
            return saved;
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(staging);
        }
    }

    static Restored restore(Path backup, Path directory) throws SQLException, IOException {
        Path source = backup.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup file does not exist");
        var original = inspect(source);
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectory(root, privateDirectory());
        Path target = root.resolve(RESTORED_NAME);
        boolean verified = false;
        try {
            Files.copy(source, target);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            var restored = inspect(target);
            if (!original.equals(restored)) throw new SQLException("Backup and restored database differ");
            verified = true;
            return new Restored(target, restored.rowCounts());
        } finally {
            if (!verified) {
                Files.deleteIfExists(target);
                Files.deleteIfExists(root);
            }
        }
    }

    private static void createPrivateDirectoryIfMissing(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(root, privateDirectory());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.getPosixFilePermissions(root).stream().anyMatch(BackupFiles::allowsOtherUsers)) {
            throw new IOException("Backup directory must be owner-only");
        }
    }

    private static boolean allowsOtherUsers(PosixFilePermission permission) {
        return permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_");
    }

    private static java.nio.file.attribute.FileAttribute<?> privateDirectory() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    private static void prune(Path root, Instant threshold) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (GENERATED.matcher(entry.getFileName().toString()).matches()
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(threshold)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static Inspection inspect(Path file) throws SQLException {
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(1000);
        var sqlite = new SQLiteDataSource(config);
        sqlite.setUrl("jdbc:sqlite:" + file.toUri().toASCIIString());
        try (var connection = sqlite.getConnection(); var statement = connection.createStatement()) {
            try (var integrity = statement.executeQuery("PRAGMA integrity_check")) {
                if (!integrity.next() || !"ok".equals(integrity.getString(1)) || integrity.next()) {
                    throw new SQLException("SQLite integrity check failed");
                }
            }
            try (var foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) throw new SQLException("SQLite foreign key check failed");
            }
            var counts = new LinkedHashMap<String, Long>();
            for (String table : TABLES) counts.put(table, count(connection, "SELECT count(*) FROM " + table));
            long readableArchives = count(connection, """
                    SELECT count(*) FROM submissions a JOIN runs r ON r.id = a.run_id AND r.user_id = a.user_id
                    JOIN prompt_snapshots s ON s.id = r.snapshot_id AND s.user_id = r.user_id
                    WHERE r.status = 'COMPLETED' AND r.raw_output IS NOT NULL AND json_valid(s.messages_json)
                    """);
            long readableDrafts = count(connection, """
                    SELECT count(*) FROM drafts d JOIN problem_cases c
                    ON c.problem_id = d.problem_id AND c.version = d.version AND c.case_id = d.case_id
                    WHERE json_valid(d.slots_json) AND d.revision > 0
                    """);
            long readableSettings = count(connection, "SELECT count(*) FROM user_settings WHERE json_valid(editor_prefs_json)");
            if (readableArchives != counts.get("submissions") || readableDrafts != counts.get("drafts")
                    || readableSettings != counts.get("user_settings")) {
                throw new SQLException("Restored business rows failed validation");
            }
            return new Inspection(counts);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("Missing row count");
            return rows.getLong(1);
        }
    }

    private record Inspection(Map<String, Long> rowCounts) {}
    record Restored(Path database, Map<String, Long> rowCounts) {}
}
