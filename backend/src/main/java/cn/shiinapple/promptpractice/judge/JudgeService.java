package cn.shiinapple.promptpractice.judge;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JudgeService {
    public static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final int MAX_NUMBER_LENGTH = 128;
    private static final Pattern NON_WHITESPACE = Pattern.compile("\\S", Pattern.UNICODE_CHARACTER_CLASS);
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxNumberLength(MAX_NUMBER_LENGTH)
                            .maxStringLength(MAX_OUTPUT_BYTES).maxNameLength(MAX_OUTPUT_BYTES).build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public Result judge(String raw, JsonNode schema, JsonNode assertions) {
        if (raw.length() > MAX_OUTPUT_BYTES || raw.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new OutputLimit();
        }

        JsonNode value;
        try {
            try (var parser = JSON.createParser(raw)) {
                for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
                    if (token.isNumeric() && parser.getTextLength() > MAX_NUMBER_LENGTH) {
                        throw new OutputLimit();
                    }
                }
            }
            value = JSON.readTree(raw);
        } catch (StreamConstraintsException exception) {
            throw new OutputLimit();
        } catch (JacksonException exception) {
            var location = exception.getLocation();
            return new Result("FORMAT_ERROR", List.of(new Check("json", "", "FAIL",
                    "INVALID_JSON", Map.of(), location == null ? Map.of() :
                    Map.of("line", location.getLineNr(), "column", location.getColumnNr()))));
        }
        var checks = new ArrayList<Check>();
        checks.add(Check.pass("json", ""));
        var violation = validate(value, schema, "");
        if (violation != null) {
            checks.add(violation);
            return new Result("FORMAT_ERROR", List.copyOf(checks));
        }
        checks.add(Check.pass("schema", ""));

        for (var assertion : assertions) {
            checks.add(checkRule(value, assertion));
        }
        return new Result(checks.stream().allMatch(check -> check.status().equals("PASS"))
                ? "PASSED" : "WRONG_ANSWER", List.copyOf(checks));
    }

    // ProblemCatalog accepts an empty schema only for optional object properties.
    private static Check validate(JsonNode value, JsonNode schema, String path) {
        if (schema.isObject() && schema.isEmpty()) {
            return null;
        }
        String type = schema.path("type").stringValue();
        boolean validType = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "integer" -> value.isIntegralNumber() || value.isBigDecimal() && isWhole(value.decimalValue());
            case "boolean" -> value.isBoolean();
            case "string" -> value.isString();
            default -> throw new IllegalStateException("Unregistered schema type");
        };
        if (!validType) {
            return new Check("schema", path, "FAIL", "TYPE", type, value.getNodeType().name());
        }
        if (value.isString() && schema.has("pattern") && !NON_WHITESPACE.matcher(value.stringValue()).find()) {
            return new Check("schema", path, "FAIL", "BLANK", NON_WHITESPACE.pattern(), Map.of());
        }
        if (value.isObject()) {
            var properties = schema.path("properties");
            var required = new HashSet<String>();
            for (var keyNode : schema.path("required")) {
                required.add(keyNode.stringValue());
            }
            for (var property : properties.properties()) {
                String key = property.getKey();
                if (!value.has(key)) {
                    if (required.contains(key)) {
                        return new Check("schema", path + "/" + key, "FAIL", "REQUIRED", key, Map.of());
                    }
                    continue;
                }
                Check child = validate(value.path(key), property.getValue(), path + "/" + key);
                if (child != null) return child;
            }
            if (!properties.propertyNames().containsAll(value.propertyNames())) {
                return new Check("schema", path, "FAIL", "EXTRA_FIELD", properties.propertyNames(), value.size());
            }
        }
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                Check child = validate(value.path(index), schema.path("items"), path + "/" + index);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static boolean isWhole(BigDecimal number) {
        return number.scale() <= 0 || number.stripTrailingZeros().scale() <= 0;
    }

    private static Check checkRule(JsonNode value, JsonNode rule) {
        String id = rule.path("id").stringValue();
        String path = rule.path("path").stringValue();
        JsonNode rows = value.at(path);
        return switch (rule.path("op").stringValue()) {
            case "arraySize" -> {
                int size = rule.path("expected").intValue();
                yield rows.size() == size ? Check.pass(id, path)
                        : new Check(id, path, "FAIL", "ARRAY_SIZE", size, rows.size());
            }
            case "uniqueBy" -> {
                var seen = new TreeSet<BigDecimal>();
                String key = rule.path("key").stringValue();
                boolean unique = true;
                for (var row : rows) {
                    if (!seen.add(row.at(key).decimalValue())) unique = false;
                }
                yield unique ? Check.pass(id, path)
                        : new Check(id, path, "FAIL", "DUPLICATE_KEY", rows.size(), seen.size());
            }
            case "mapEquals" -> checkMapping(id, path, rows, rule);
            default -> throw new IllegalStateException("Unregistered judge operation");
        };
    }

    private static Check checkMapping(String id, String path, JsonNode rows, JsonNode rule) {
        String key = rule.path("key").stringValue();
        String value = rule.path("value").stringValue();
        var actual = new TreeMap<BigDecimal, Boolean>();
        for (var row : rows) {
            actual.put(row.at(key).decimalValue(), row.at(value).booleanValue());
        }
        var expected = new LinkedHashMap<String, Boolean>();
        var observed = new LinkedHashMap<String, Boolean>();
        int extra = actual.size();
        for (var entry : rule.path("expected").properties()) {
            BigDecimal length = new BigDecimal(entry.getKey());
            boolean wanted = entry.getValue().booleanValue();
            Boolean got = actual.get(length);
            if (got != null) extra--;
            if (!Boolean.valueOf(wanted).equals(got)) {
                expected.put(entry.getKey(), wanted);
                observed.put(entry.getKey(), got);
            }
        }
        return expected.isEmpty() && extra == 0 ? Check.pass(id, path)
                : new Check(id, path, "FAIL", "BOUNDARY_MAPPING",
                        Map.of("boundaries", expected, "unexpectedCount", 0),
                        Map.of("boundaries", observed, "unexpectedCount", extra));
    }

    public record Result(String verdict, List<Check> checks) {
    }

    public record Check(String id, String path, String status, String code, Object expected, Object actual) {
        static Check pass(String id, String path) {
            return new Check(id, path, "PASS", "", Map.of(), Map.of());
        }
    }

    public static class OutputLimit extends RuntimeException {
    }
}
