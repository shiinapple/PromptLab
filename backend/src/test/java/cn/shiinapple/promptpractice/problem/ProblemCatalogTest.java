package cn.shiinapple.promptpractice.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class ProblemCatalogTest {
    private static final String PACK = "classpath:/content/problems/002/v1/";

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

        assertThatThrownBy(() -> catalog.loadPublished("001")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("release for 001");
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

    private static ProblemCatalog withReplacement(String path, String json) {
        return new ProblemCatalog(new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.equals(PACK + path)) {
                    return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResource(location);
            }
        });
    }
}
