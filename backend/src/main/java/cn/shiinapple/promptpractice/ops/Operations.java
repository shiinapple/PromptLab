package cn.shiinapple.promptpractice.ops;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;

import cn.shiinapple.promptpractice.auth.Accounts;
import cn.shiinapple.promptpractice.maintenance.RunMaintenance;
import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.stereotype.Component;

@Component
public class Operations {
    private final Accounts accounts;

    private final RunMaintenance maintenance;
    private final BackupFiles backups;

    Operations(Accounts accounts, RunMaintenance maintenance, BackupFiles backups) {
        this.accounts = accounts;
        this.maintenance = maintenance;
        this.backups = backups;
    }

    public int execute(String[] args) {
        try {
            if (args.length == 1 && "maintain".equals(args[0])) {
                System.out.println(maintenance.sweep());
            } else if (args.length == 2) {
                switch (args[0]) {
                    case "create-user" -> createUser(args[1]);
                    case "disable-user" -> accounts.disable(args[1]);
                    case "delete-user" -> accounts.deleteDisabled(args[1]);
                    case "backup" -> System.out.println(backups.backup(Path.of(args[1])));
                    default -> throw usage();
                }
            } else {
                throw usage();
            }
            return 0;
        } catch (PracticeError | IllegalArgumentException error) {
            System.err.println(error.getMessage());
        } catch (IOException | SQLException error) {
            System.err.println("Operation failed: " + error.getClass().getSimpleName());
        }
        return 1;
    }

    public static int restore(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: --ops restore BACKUP_FILE NEW_DIRECTORY");
            return 1;
        }
        try {
            var restored = BackupFiles.restore(Path.of(args[1]), Path.of(args[2]));
            System.out.println("Restored and verified " + restored.database());
            System.out.println("Row counts " + restored.rowCounts());
            return 0;
        } catch (IOException | SQLException | IllegalArgumentException error) {
            System.err.println("Restore failed: " + error.getClass().getSimpleName());
            return 1;
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("""
                Usage: --ops create-user EMAIL | disable-user EMAIL | delete-user EMAIL
                       --ops maintain | backup DIRECTORY | restore BACKUP_FILE NEW_DIRECTORY
                """);
    }

    private void createUser(String email) throws IOException {
        char[] password;
        if (System.console() != null) {
            password = System.console().readPassword("Password: ");
            if (password == null) throw new IOException("Missing input");
        } else {
            byte[] bytes = System.in.readNBytes(1026);
            try {
                int end = 0;
                while (end < bytes.length && bytes[end] != '\n') end++;
                if (end > 0 && bytes[end - 1] == '\r') end--;
                var decoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, end));
                password = new char[decoded.remaining()];
                decoded.get(password);
                if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        try {
            System.out.println(accounts.create(email, CharBuffer.wrap(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
