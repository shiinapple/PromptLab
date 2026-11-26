package cn.shiinapple.promptpractice.pocsqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
class StackTest {
    @TempDir
    static Path folder;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("poc.db", () -> folder.resolve("web.sqlite").toString());
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void bootStartsWithFlywayAndMvcSerializesJdbcResult() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/poc/status")).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var json = new ObjectMapper().readTree(response.body());
            assertEquals("3.54.1", json.get("sqlite").asString());
            assertEquals(0, json.get("runs").asLong());
        }
    }
}
