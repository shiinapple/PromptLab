package cn.shiinapple.promptpractice.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.DefaultResourceLoader;

import cn.shiinapple.promptpractice.problem.ProblemCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JudgeServiceTest {
    private static final ProblemCatalog.ProblemPack PACK =
            new ProblemCatalog(new DefaultResourceLoader()).loadPublished("002");
    private static final JudgeService JUDGE = new JudgeService();
    private static final String VALID = PACK.fixtures().path(0).path("rawOutput").stringValue();
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final JsonNode NO_ASSERTIONS = JSON.readTree("[]");
    private static final JsonNode OPTIONAL_BOOLEAN_SCHEMA = JSON.readTree("""
            {"type":"object","required":["a"],"additionalProperties":false,
             "properties":{"a":{"type":"integer"},"b":{"type":"boolean"}}}
            """);
    private static final JsonNode STRING_SCHEMA = JSON.readTree("""
            {"type":"object","required":["reply"],"additionalProperties":false,
             "properties":{"reply":{"type":"string","pattern":"\\\\S"},
                           "name":{"type":"string"},"in_character":{}}}
            """);

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedCases")
    void followsPackFixtures(String id, String raw, String verdict, java.util.List<String> expectedFailed) {
        var result = check(raw);
        assertThat(result.verdict()).isEqualTo(verdict);
        assertThat(result.checks().stream().filter(c -> c.status().equals("FAIL"))
                .map(JudgeService.Check::id).filter(key -> !key.equals("json") && !key.equals("schema")).toList())
                .isEqualTo(expectedFailed);
    }

    static Stream<Arguments> publishedCases() {
        return StreamSupport.stream(PACK.fixtures().spliterator(), false)
                .map(fixture -> Arguments.of(fixture.path("id").stringValue(),
                        fixture.path("rawOutput").stringValue(), fixture.path("verdict").stringValue(),
                        StreamSupport.stream(fixture.path("failedCheckIds").spliterator(), false)
                                .map(node -> node.stringValue()).toList()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void handlesStrictJsonAndRuleEdges(String id, String raw, String verdict) {
        assertThat(check(raw).verdict()).isEqualTo(verdict);
    }

    static Stream<Arguments> variants() {
        return Stream.of(
                Arguments.of("standard whitespace", " \r\n" + VALID + "\t ", "PASSED"),
                Arguments.of("decimal integer", VALID.replace(":13,", ":13.0,"), "PASSED"),
                Arguments.of("exponent integer", VALID.replace(":13,", ":130e-1,"), "PASSED"),
                Arguments.of("fraction using exact scale", VALID.replace(":13,", ":13.00000000000000000001,"), "FORMAT_ERROR"),
                Arguments.of("leading prose", "Here is JSON: " + VALID, "FORMAT_ERROR"),
                Arguments.of("trailing prose", VALID + " done", "FORMAT_ERROR"),
                Arguments.of("trailing JSON", VALID + " {}", "FORMAT_ERROR"),
                Arguments.of("trailing comma", "{\"cases\":[],}", "FORMAT_ERROR"),
                Arguments.of("comment", "/* comment */" + VALID, "FORMAT_ERROR"),
                Arguments.of("duplicate key", "{\"cases\":[],\"cases\":[]}", "FORMAT_ERROR"),
                Arguments.of("non JSON number", VALID.replace(":13,", ":NaN,"), "FORMAT_ERROR"),
                Arguments.of("missing root field", "{}", "FORMAT_ERROR"),
                Arguments.of("root array", "[]", "FORMAT_ERROR"),
                Arguments.of("incorrect array type", "{\"cases\":{}}", "FORMAT_ERROR"),
                Arguments.of("extra field", VALID.replace("\"length\":13", "\"unknown\":0,\"length\":13"), "FORMAT_ERROR"),
                Arguments.of("missing boundary", VALID.replace("{\"length\":13,\"expectedValid\":false},", ""), "WRONG_ANSWER"),
                Arguments.of("duplicate numeric key", VALID.replace(":13,", ":12.0,"), "WRONG_ANSWER"),
                Arguments.of("extra boundary", VALID.replace(":13,", ":14,"), "WRONG_ANSWER"),
                Arguments.of("large integer", VALID.replace(":13,", ":1e1000000,"), "WRONG_ANSWER")
        );
    }

    @ParameterizedTest
    @MethodSource("optionalBooleanCases")
    void validatesDeclaredFieldsAndRejectsUndeclaredNames(String raw, String code, String path) {
        assertStructureResult(raw, OPTIONAL_BOOLEAN_SCHEMA, code, path);
    }

    static Stream<Arguments> optionalBooleanCases() {
        return Stream.of(
                Arguments.of("{\"a\":1}", "", ""),
                Arguments.of("{\"a\":1,\"b\":\"not-bool\"}", "TYPE", "/b"),
                Arguments.of("{\"a\":1,\"zzz\":2}", "EXTRA_FIELD", "")
        );
    }

    @ParameterizedTest
    @MethodSource("stringCases")
    void checksStringTypeAndUnicodeNonWhitespace(String raw, String code, String path) {
        assertStructureResult(raw, STRING_SCHEMA, code, path);
    }

    static Stream<Arguments> stringCases() {
        return Stream.of(
                Arguments.of("{}", "REQUIRED", "/reply"),
                Arguments.of("{\"reply\":42}", "TYPE", "/reply"),
                Arguments.of("{\"reply\":\"\"}", "BLANK", "/reply"),
                Arguments.of("{\"reply\":\" \\t\\n\"}", "BLANK", "/reply"),
                Arguments.of("{\"reply\":\"\u3000\"}", "BLANK", "/reply"),
                Arguments.of("{\"reply\":\"\u00A0\"}", "BLANK", "/reply"),
                Arguments.of("{\"reply\":\"\u3000守夜人 \"}", "", ""),
                Arguments.of("{\"reply\":\"yes\",\"name\":\"\"}", "", ""),
                Arguments.of("{\"reply\":\"yes\",\"name\":null}", "TYPE", "/name")
        );
    }

    @ParameterizedTest
    @MethodSource("unconstrainedValues")
    void acceptsAnyJsonTypeForAnOptionalEmptySchema(String value) {
        assertStructureResult("{\"reply\":\"yes\",\"in_character\":" + value + "}", STRING_SCHEMA, "", "");
    }

    static Stream<String> unconstrainedValues() {
        return Stream.of("null", "true", "123.5", "\"\"", "[1,false]", "{\"anything\":0}");
    }

    private static void assertStructureResult(String raw, JsonNode schema, String code, String path) {
        var result = JUDGE.judge(raw, schema, NO_ASSERTIONS);
        assertThat(result.verdict()).isEqualTo(code.isEmpty() ? "PASSED" : "FORMAT_ERROR");
        assertThat(result.checks()).extracting(JudgeService.Check::id).containsExactly("json", "schema");
        var schemaCheck = result.checks().get(1);
        assertThat(schemaCheck.status()).isEqualTo(code.isEmpty() ? "PASS" : "FAIL");
        assertThat(schemaCheck.code()).isEqualTo(code);
        assertThat(schemaCheck.path()).isEqualTo(path);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limits")
    void enforcesLimits(String id, String raw) {
        assertThatThrownBy(() -> check(raw)).isInstanceOf(JudgeService.OutputLimit.class);
    }

    static Stream<Arguments> limits() {
        return Stream.of(
                Arguments.of("UTF-8 bytes", "界".repeat(JudgeService.MAX_OUTPUT_BYTES / 3 + 1)),
                Arguments.of("nesting", "[".repeat(66) + "]".repeat(66)),
                Arguments.of("number", VALID.replace(":13,", ":" + "9".repeat(129) + ",")),
                Arguments.of("signed number token", VALID.replace(":13,", ":-" + "9".repeat(128) + ","))
        );
    }

    private static JudgeService.Result check(String raw) {
        return JUDGE.judge(raw, PACK.schema(), PACK.publicCase().path("assertions"));
    }
}
