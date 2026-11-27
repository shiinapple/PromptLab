package cn.shiinapple.promptpractice.practice;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ApiInput {
    private static final JsonMapper INPUT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ApiInput() {}

    public static JsonNode read(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
        byte[] data = request.getInputStream().readNBytes(limit + 1);
        if (data.length > limit) throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
            return INPUT.readTree(utf8);
        } catch (JacksonException | java.nio.charset.CharacterCodingException error) {
            throw new PracticeError(400, "INVALID_JSON");
        }
    }

    public static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new PracticeError(400, "INVALID_REQUEST");
        if (!allowed.containsAll(node.propertyNames())) throw new PracticeError(400, "UNKNOWN_FIELD");
    }

    public static String string(JsonNode node, int maxBytes) {
        if (!node.isString()) throw new PracticeError(400, "INVALID_REQUEST");
        String text = node.stringValue();
        if (text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw new PracticeError(400, "INVALID_JSON");
        return text;
    }

    public static int integer(JsonNode node, int min, int max) {
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) {
            throw new PracticeError(400, "INVALID_REQUEST");
        }
        return node.intValue();
    }

    public static Integer problemVersion(String value) {
        if (value == null) return null;
        if (!value.matches("[0-9]+")) throw new PracticeError(400, "INVALID_REQUEST");
        BigInteger version = new BigInteger(value);
        if (version.signum() == 0) throw new PracticeError(400, "INVALID_REQUEST");
        if (version.bitLength() > 31) throw new PracticeError(404, "RESOURCE_NOT_FOUND");
        return version.intValue();
    }

}
