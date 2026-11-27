package cn.shiinapple.promptpractice.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class ProblemCatalogTest {
    private static final String PACK = "classpath:/content/problems/002/v1/";
    private static final String OBJECT_SCHEMA = """
            {
              "$schema":"https://json-schema.org/draft/2020-12/schema",
              "type":"object","additionalProperties":false,"required":%s,"properties":%s
            }
            """;

    @Test
    void preservesThePublished002Version1ContentHash() {
        var pack = new ProblemCatalog(new DefaultResourceLoader()).loadVersion("002", 1);

        assertThat(pack.contentHash())
                .isEqualTo("5cfa92e677ba4b90b12493b5a3c920c578b2b116521070721baf604d369f23c8");
    }

    @Test
    void loadsThePublishedVersionAndCaseFromPackagedResources() {
        var pack = new ProblemCatalog(new DefaultResourceLoader()).loadPublished("002");

        assertThat(pack.problemId()).isEqualTo("002");
        assertThat(pack.version()).isEqualTo(1);
        assertThat(pack.caseId()).isEqualTo("case-1");
        assertThat(pack.translations()).containsOnlyKeys("zh-CN", "en");
        assertThat(pack.publicCase().path("variables").path("requirement").stringValue()).contains("5、6、7、11、12、13");
        assertThat(pack.schema().at("/properties/cases/items/properties/length/type").stringValue()).isEqualTo("integer");
        assertThat(pack.fixtures()).extracting(fixture -> fixture.path("verdict").stringValue())
                .containsExactly("PASSED", "FORMAT_ERROR", "FORMAT_ERROR", "WRONG_ANSWER");
    }

    @Test
    void rejectsAProblemNotInTheReleaseManifest() {
        var catalog = new ProblemCatalog(new DefaultResourceLoader());

        assertThatThrownBy(() -> catalog.loadPublished("009")).isInstanceOf(ProblemCatalog.UnknownProblem.class)
                .hasMessageContaining("release for 009");
    }

    @Test
    void rejectsACaseWithAMissingVariable() {
        var catalog = withReplacement("cases/case-1.json", """
                {"caseId":"case-1","inputLocale":"zh-CN","variables":{}}
                """);

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Case variables", "requirement");
    }

    @Test
    void rejectsALocalizedTemplateWithAMissingPlaceholder() {
        var catalog = withReplacement("i18n/en.json", """
                {"title":"Test cases","description":"Generate cases","defaultSlots":{"user":"Generate cases."}}
                """);

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("i18n/en.json", "Variable placeholders");
    }

    @Test
    void rejectsAnInvalidNestedSchema() {
        var catalog = withReplacement("schema.json", """
                {
                  "$schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object","additionalProperties":false,"required":["cases"],
                  "properties":{"cases":{"type":"array","items":{"type":"bool"}}}
                }
                """);

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema.json/cases/items", "Unsupported schema type");
    }

    @Test
    void loadsAStructureOnlyCaseWithStringAndOptionalSchemas() {
        var catalog = withReplacements(Map.of(
                "schema.json", OBJECT_SCHEMA.formatted("[\"reply\"]", """
                        {"reply":{"type":"string","pattern":"\\\\S"},
                         "name":{"type":"string"},"in_character":{},
                         "metadata":{"type":"object","required":[],"additionalProperties":false,"properties":{}}}
                        """),
                "cases/case-1.json", """
                        {"caseId":"case-1","inputLocale":"zh-CN","variables":{"requirement":"input"},"assertions":[]}
                        """,
                "fixtures/case-1.json", """
                        [{"id":"valid","rawOutput":"{\\"reply\\":\\"yes\\"}","verdict":"PASSED","failedCheckIds":[]}]
                        """));

        var pack = catalog.loadPublished("002");

        assertThat(pack.publicCase().path("assertions").isArray()).isTrue();
        assertThat(pack.publicCase().path("assertions").size()).isZero();
        assertThat(pack.schema().at("/properties/reply/pattern").stringValue()).isEqualTo("\\S");
        assertThat(pack.schema().at("/properties/in_character").isObject()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"assertions\":null", ",\"assertions\":{}"})
    void requiresAnExplicitAssertionsArray(String assertions) {
        var catalog = withReplacement("cases/case-1.json", """
                {"caseId":"case-1","inputLocale":"zh-CN","variables":{"requirement":"input"}%s}
                """.formatted(assertions));

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing case assertions");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"type\":\"array\",\"items\":{}}",
            "{\"type\":\"string\",\"minLength\":1}",
            "{\"type\":\"string\",\"pattern\":\"^.+$\"}",
            "{\"type\":\"string\",\"pattern\":null}",
            "{\"type\":\"string\",\"enum\":[\"yes\"]}",
            "{\"type\":\"string\",\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}",
            "{\"type\":[\"string\",\"boolean\"]}",
            "{\"type\":\"object\",\"required\":[],\"additionalProperties\":true,\"properties\":{}}"
    })
    void rejectsUnsupportedSchemasForARequiredProperty(String schema) {
        var catalog = withReplacement("schema.json", OBJECT_SCHEMA.formatted("[\"a\"]", "{\"a\":" + schema + "}"));

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema.json/a");
    }

    @Test
    void rejectsAnUndeclaredRequiredField() {
        var catalog = withReplacement("schema.json", OBJECT_SCHEMA.formatted("[\"a\"]", "{}"));

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Required fields must be declared in properties");
    }

    @Test
    void rejectsAnEmptyRootSchema() {
        var catalog = withReplacement("schema.json", "{}");

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema.json requires a 2020-12 object schema");
    }

    @Test
    void rejectsASchemaWithARemoteReferenceBeforeCallingTheMock() throws IOException {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var catalog = withReplacement("schema.json", """
                    {
                      "$schema":"https://json-schema.org/draft/2020-12/schema",
                      "type":"object","additionalProperties":false,"required":["cases"],
                      "properties":{"cases":{"type":"array","items":{
                        "type":"boolean","$ref":"http://127.0.0.1:%d/schema"
                      }}}
                    }
                    """.formatted(server.getAddress().getPort()));

            assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("schema.json/cases/items", "Invalid scalar schema");
            assertThat(calls.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loadsVariablesFromTheSystemSlotAndFixedUserMessage() {
        var catalog = withReplacements(Map.of(
                "problem.json", problem("""
                        {"system":{"requiredVariables":["persona"]}}
                        """, """
                        [
                          {"role":"system","parts":[{"type":"slot","name":"system"}]},
                          {"role":"user","parts":[{"type":"fixedText","text":"Question: "},
                                                 {"type":"caseVariable","name":"question"}]}
                        ]
                        """),
                "i18n/en.json", systemTranslation(),
                "i18n/zh-CN.json", systemTranslation(),
                "cases/case-1.json", """
                        {"caseId":"case-1","inputLocale":"zh-CN",
                         "variables":{"persona":"守夜人","question":"你是谁？"},
                         "assertions":[{"id":"boundary-mapping","op":"mapEquals"}]}
                        """));

        var pack = catalog.loadPublished("002");

        assertThat(pack.problem().path("slots").propertyNames()).containsExactly("system");
        assertThat(pack.publicCase().path("variables").propertyNames())
                .containsExactlyInAnyOrder("persona", "question");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"requirement\":\"input\"}",
            "{\"context\":\"input\"}",
            "{\"requirement\":\"input\",\"context\":\"input\",\"extra\":\"input\"}"
    })
    void rejectsMissingOrExtraVariablesForSlotAndMessage(String variables) {
        var catalog = withReplacements(Map.of(
                "problem.json", problem("""
                        {"user":{"requiredVariables":["requirement"]}}
                        """, """
                        [
                          {"role":"system","parts":[{"type":"caseVariable","name":"context"}]},
                          {"role":"user","parts":[{"type":"slot","name":"user"}]}
                        ]
                        """),
                "cases/case-1.json", """
                        {"caseId":"case-1","inputLocale":"zh-CN","variables":%s}
                        """.formatted(variables)));

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Case variables", "requirement", "context");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            [] | Missing messages
            [{"role":"tool","parts":[{"type":"slot","name":"user"}]}] | Unknown message role
            [{"role":"user","parts":[{"type":"text","text":"input"}]}] | Unknown message part type
            [{"role":"user","parts":[{"type":"slot","name":"system"}]}] | Unknown slot
            [{"role":"user","parts":[{"type":"fixedText","text":"input"}]}] | Expected exactly one reference to slot user
            [{"role":"user","parts":[{"type":"slot","name":"user"},{"type":"slot","name":"user"}]}] | Expected exactly one reference to slot user
            [{"role":"user","parts":[{"type":"fixedText","text":null}]}] | Invalid fixed text
            [{"role":"user","parts":[{"type":"caseVariable","name":""}]}] | Invalid case variable name
            [{"role":"user","parts":{}}] | Invalid message parts
            """)
    void rejectsInvalidMessagePlans(String messages, String error) {
        var catalog = withReplacement("problem.json", problem("""
                {"user":{"requiredVariables":["requirement"]}}
                """, messages));

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(error);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"user\":{},\"system\":{}}"})
    void requiresExactlyOneEditableSlot(String slots) {
        var catalog = withReplacement("problem.json", problem(slots, "[]"));

        assertThatThrownBy(() -> catalog.loadPublished("002")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected one editable slot");
    }

    private static String problem(String slots, String messages) {
        return """
                {"problemId":"002","version":1,"caseIds":["case-1"],"slots":%s,"messages":%s}
                """.formatted(slots, messages);
    }

    private static String systemTranslation() {
        return """
                {"title":"Watcher","description":"Describe the watcher","defaultSlots":{"system":"You are {{persona}}."}}
                """;
    }

    private static ProblemCatalog withReplacement(String path, String json) {
        return withReplacements(Map.of(path, json));
    }

    private static ProblemCatalog withReplacements(Map<String, String> replacements) {
        return new ProblemCatalog(new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.startsWith(PACK)) {
                    String json = replacements.get(location.substring(PACK.length()));
                    if (json != null) {
                        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
                    }
                }
                return super.getResource(location);
            }
        });
    }
}
