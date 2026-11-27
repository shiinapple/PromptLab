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

class JudgeServiceTest {
    private static final ProblemCatalog.ProblemPack PACK =
            new ProblemCatalog(new DefaultResourceLoader()).loadPublished("002");
    private static final JudgeService JUDGE = new JudgeService();
    private static final String VALID = PACK.fixtures().path(0).path("rawOutput").stringValue();

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
