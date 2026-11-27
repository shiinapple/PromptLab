package cn.shiinapple.promptpractice.ops;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import cn.shiinapple.promptpractice.auth.Accounts;
import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.stereotype.Component;

@Component
public class Operations {
    private final Accounts accounts;

    Operations(Accounts accounts) {
        this.accounts = accounts;
    }

    public int execute(String[] args) {
        try {
            if (args.length != 2 || !"create-user".equals(args[0])) {
                throw new IllegalArgumentException("Usage: --ops create-user EMAIL (password on stdin)");
            }
            createUser(args[1]);
            return 0;
        } catch (PracticeError error) {
            System.err.println(error.getMessage());
            return 1;
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            return 1;
        } catch (IOException error) {
            System.err.println("Could not read password from stdin");
            return 1;
        }
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
