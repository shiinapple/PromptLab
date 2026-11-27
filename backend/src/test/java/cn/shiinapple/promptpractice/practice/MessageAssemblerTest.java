package cn.shiinapple.promptpractice.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

class MessageAssemblerTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("goldens")
    void matchesSharedGolden(String id, JsonNode plan, JsonNode variables, JsonNode test) {
        Map<String, String> slots = Map.of("user", test.at("/slots/user").stringValue());
        if (test.has("error")) {
            assertThatThrownBy(() -> MessageAssembler.assemble(plan, slots, variables))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(test.path("error").stringValue());
        } else {
            var actual = MessageAssembler.assemble(plan, slots, variables);
            JsonNode messages = PracticeRuns.JSON.valueToTree(actual);
            assertThat(messages).isEqualTo(test.path("expected"));
        }
    }

    static Stream<Arguments> goldens() throws Exception {
        var shared = PracticeRuns.JSON.readTree(Files.readString(Path.of("../content/fixtures/messages.json")));
        return StreamSupport.stream(shared.path("cases").spliterator(), false)
                .map(test -> Arguments.of(test.path("id").stringValue(), shared.path("plan"), shared.path("variables"), test));
    }
}
