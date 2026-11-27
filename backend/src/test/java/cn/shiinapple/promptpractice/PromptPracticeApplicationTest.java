package cn.shiinapple.promptpractice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class PromptPracticeApplicationTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("prompt-practice.database.path", () -> directory.resolve("http.sqlite3").toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Test
    void reportsOnlyMinimalReadiness() throws Exception {
        var response = send("GET", "/api/health");
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(json.readTree("""
                {"status":"UP"}
                """), json.readTree(response.body()));
    }

    @Test
    void reportsUnavailableWhileRefusingTraffic() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertProblem(send("GET", "/api/health"), 503,
                    "TEMPORARILY_UNAVAILABLE", "errors.temporarilyUnavailable");
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void servesConsistentErrorsForMissingRoutes() throws Exception {
        assertProblem(send("GET", "/unmapped"), 404, "RESOURCE_NOT_FOUND", "errors.resourceNotFound");
        var response = send("GET", "/api/unmapped?private=fixture-query");
        assertProblem(response, 404, "RESOURCE_NOT_FOUND", "errors.resourceNotFound");
        assertFalse(response.body().contains("fixture-query"));
    }

    @Test
    void preservesHttpMethodErrorAndAllowHeader() throws Exception {
        var response = send("POST", "/api/health");
        assertProblem(response, 405, "METHOD_NOT_ALLOWED", "errors.methodNotAllowed");
        assertTrue(response.headers().firstValue("Allow").orElseThrow().contains("GET"));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code, String messageKey) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("application/problem+json"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var body = json.readTree(response.body());
        assertEquals("about:blank", body.path("type").asText());
        assertEquals(status, body.path("status").asInt());
        assertEquals(code, body.path("code").asText());
        assertEquals(messageKey, body.path("messageKey").asText());
        UUID.fromString(body.path("requestId").asText());
        assertFalse(body.has("trace"));
        assertFalse(body.has("exception"));
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
