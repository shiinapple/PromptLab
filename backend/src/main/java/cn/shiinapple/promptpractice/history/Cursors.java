package cn.shiinapple.promptpractice.history;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.stereotype.Component;

@Component
class Cursors {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] key = new SecureRandom().generateSeed(32);

    String encode(String owner, String scope, Position position) {
        String payload = position.time() + ":" + position.id();
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + ENCODER.encodeToString(sign(owner, scope, payload));
    }

    Position read(String owner, String scope, String token) {
        if (token == null) return null;
        if (token.length() > 180) throw invalidCursor();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw invalidCursor();
            String payload = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(DECODER.decode(parts[1]), sign(owner, scope, payload))) throw invalidCursor();
            int separator = payload.indexOf(':');
            return new Position(Long.parseLong(payload.substring(0, separator)), payload.substring(separator + 1));
        } catch (IllegalArgumentException malformed) {
            throw invalidCursor();
        }
    }

    private byte[] sign(String owner, String scope, String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, mac.getAlgorithm()));
            return mac.doFinal(("v1\n" + owner + "\n" + scope + "\n" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cursor signature is unavailable", error);
        }
    }

    private static PracticeError invalidCursor() {
        return new PracticeError(400, "INVALID_CURSOR");
    }

    record Position(long time, String id) {}
}
