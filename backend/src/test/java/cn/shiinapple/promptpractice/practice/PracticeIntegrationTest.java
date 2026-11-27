package cn.shiinapple.promptpractice.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import cn.shiinapple.promptpractice.judge.JudgeService;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;
import tools.jackson.databind.JsonNode;

@ActiveProfiles("local-practice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PracticeIntegrationTest {
    @TempDir
    static Path directory;
    @LocalServerPort
    int port;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource data;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    ProblemCatalog catalog;
    @Autowired
    JudgeService judge;

    private final HttpClient client = HttpClient.newHttpClient();
    private String token;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("prompt-practice.database.path", () -> directory.resolve("practice.sqlite3").toString());
    }

    @BeforeEach
    void session() throws Exception {
        token = get("/api/practice/session").body().path("token").stringValue();
    }

    @Test
    void readsBothLocalesWithoutExposingRulesAndRetainsExactRunState() throws Exception {
        var english = get("/api/problems/002?locale=en").body();
        var chinese = get("/api/problems/002").body();
        assertThat(english.path("title").stringValue()).isEqualTo("Requirements to Test Cases");
        assertThat(english.at("/cases/0/variables")).isEqualTo(chinese.at("/cases/0/variables"));
        assertThat(english.toString()).doesNotContain("assertions", "boundary-mapping", "schema");
        assertThat(get("/api/problems").body().at("/items/1/problemId").stringValue()).isEqualTo("002");
        String key = UUID.randomUUID().toString();
        var original = create(key, "查看：{{requirement}}");
        assertThat(original.status()).isEqualTo(201);
        String id = id(original);
        assertThat(original.location()).isEqualTo("/api/runs/" + id);
        var replay = create(key, "查看：{{requirement}}");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(create(key, "另一个 Prompt {{requirement}}").body().path("code").stringValue())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        var next = create(UUID.randomUUID().toString(), "另一个 Prompt {{requirement}}");
        assertThat(id(next)).isNotEqualTo(id);

        String raw = " \r\n" + catalog.loadPublished("002").fixtures().path(0).path("rawOutput").stringValue() + "\t\n";
        var result = complete(id, raw);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("verdict").stringValue()).isEqualTo("PASSED");
        assertThat(complete(id, raw).body()).isEqualTo(result.body());
        assertThat(complete(id, raw.strip()).status()).isEqualTo(409);
        var reloaded = get("/api/runs/" + id).body();
        assertThat(reloaded.path("rawOutput").stringValue()).isEqualTo(raw);
        assertThat(reloaded.path("messages")).isEqualTo(original.body().path("messages"));
        assertThat(next.body().path("messages")).isNotEqualTo(reloaded.path("messages"));
    }

    @Test
    void listsPublishedProblemsAndGradesSample001UsingItsSystemSlot() throws Exception {
        var items = get("/api/problems?locale=en").body().path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("problemId").stringValue()).isEqualTo("001");
        assertThat(items.get(0).path("title").stringValue()).isEqualTo("The Night Watcher of Moon Harbor");
        assertThat(items.get(0).path("difficulty").stringValue()).isEqualTo("beginner");
        assertThat(get("/api/problems/001").body().at("/cases/0/grading").stringValue()).isEqualTo("STRUCTURE_ONLY");
        assertThat(get("/api/problems/002").body().at("/cases/0/grading").stringValue()).isEqualTo("RULES");
        for (String unknown : new String[]{"009", "invalid"}) {
            var missing = get("/api/problems/" + unknown);
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.body().path("code").stringValue()).isEqualTo("RESOURCE_NOT_FOUND");
            var intent = new java.util.HashMap<>(createBody("{{requirement}}"));
            intent.put("problemId", unknown);
            assertThat(post("/api/runs", intent, UUID.randomUUID().toString(), token).status()).isEqualTo(404);
        }
        var pack = catalog.loadPublished("001");
        for (var fixture : pack.fixtures()) {
            var intent = new java.util.HashMap<>(createBody("{{requirement}}"));
            intent.put("problemId", "001");
            intent.put("slots", Map.of("system", "你是艾琳。"));
            var created = post("/api/runs", intent, UUID.randomUUID().toString(), token);
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.body().at("/messages/0/role").stringValue()).isEqualTo("system");
            assertThat(created.body().at("/messages/0/content").stringValue()).isEqualTo("你是艾琳。");
            assertThat(created.body().at("/messages/1/content")).isEqualTo(pack.publicCase().at("/variables/question"));
            var result = complete(id(created), fixture.path("rawOutput").stringValue());
            assertThat(result.body().path("verdict")).as(fixture.path("id").stringValue())
                    .isEqualTo(fixture.path("verdict"));
            if ("missing-reply".equals(fixture.path("id").stringValue())) {
                assertThat(result.body().at("/checks/1/path").stringValue()).isEqualTo("/reply");
                assertThat(result.body().at("/checks/1/code").stringValue()).isEqualTo("REQUIRED");
            }
        }
    }

    @Test
    void usesTheExistingRunContractForDirectMode() throws Exception {
        String key = UUID.randomUUID().toString();
        var direct = new java.util.HashMap<>(createBody("{{requirement}}"));
        direct.put("mode", "DIRECT");
        var created = post("/api/runs", direct, key, token);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("mode").stringValue()).isEqualTo("DIRECT");
        assertThat(post("/api/runs", direct, key, token).body()).isEqualTo(created.body());
        assertThat(create(key, "{{requirement}}").body().path("code").stringValue()).isEqualTo("IDEMPOTENCY_CONFLICT");
        direct.put("baseUrl", "https://model.example");
        assertThat(post("/api/runs", direct, UUID.randomUUID().toString(), token).body().path("code").stringValue())
                .isEqualTo("UNKNOWN_FIELD");
        direct.remove("baseUrl");
        direct.put("mode", "PROXY");
        assertThat(post("/api/runs", direct, UUID.randomUUID().toString(), token).body().path("code").stringValue())
                .isEqualTo("INVALID_MODE");
        String raw = catalog.loadPublished("002").fixtures().path(0).path("rawOutput").stringValue();
        assertThat(complete(id(created), raw).body().path("verdict").stringValue()).isEqualTo("PASSED");
    }

    @Test
    void guardsMutationAndRejectsBadFieldsAndOversizeAtBothLevels() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(post("/api/runs", createBody("{{requirement}}"), key, null).status()).isEqualTo(403);
        var crossSite = client.send(request("/api/practice/session").header("Sec-Fetch-Site", "cross-site").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(crossSite.statusCode()).isEqualTo(403);

        var extra = PracticeRuns.JSON.createObjectNode().setAll((tools.jackson.databind.node.ObjectNode)
                PracticeRuns.JSON.valueToTree(createBody("{{requirement}}")));
        extra.put("schema", "{}");
        assertThat(post("/api/runs", extra, key, token).body().path("code").stringValue()).isEqualTo("UNKNOWN_FIELD");
        assertThat(create(key, "removed variable").status()).isEqualTo(422);
        assertThat(create(key, "界".repeat(3000) + "{{requirement}}").status()).isEqualTo(413);
        var duplicate = request("/api/runs").header("Content-Type", "application/json").header("X-Practice-CSRF", token)
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString("{\"version\":1,\"version\":2}")).build();
        assertThat(client.send(duplicate, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
        String id = id(create(key, "{{requirement}}"));
        assertThat(post("/api/runs/" + id + "/result", Map.of("status", "COMPLETED", "rawOutput", "{}",
                "headers", Map.of()), null, token).status()).isEqualTo(400);
        assertThat(complete(id, "界".repeat(90_000)).status()).isEqualTo(413);
        byte[] bytes = " ".repeat(1_600_000).getBytes();
        var chunked = request("/api/runs/" + id + "/result").header("Content-Type", "application/json")
                .header("X-Practice-CSRF", token).POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(bytes))).build();
        assertThat(client.send(chunked, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
        assertThat(get("/api/runs/" + id).body().path("status").stringValue()).isEqualTo("PENDING");
    }

    @Test
    void validatesSlotNamesAgainstThePackAfterCheckingIdempotency() throws Exception {
        String key = UUID.randomUUID().toString();
        var template = "{{requirement}}";
        var intent = new java.util.HashMap<>(createBody(template));
        intent.put("slots", java.util.List.of(template));
        var invalidShape = post("/api/runs", intent, key, token);
        assertThat(invalidShape.status()).isEqualTo(400);
        assertThat(invalidShape.body().path("code").stringValue()).isEqualTo("INVALID_REQUEST");

        for (var slots : java.util.List.of(Map.<String, String>of(), Map.of("system", template),
                Map.of("user", template, "system", template))) {
            intent.put("slots", slots);
            var invalidSlot = post("/api/runs", intent, key, token);
            assertThat(invalidSlot.status()).isEqualTo(422);
            assertThat(invalidSlot.body().path("code").stringValue()).isEqualTo("INVALID_SLOT");
        }

        var original = create(key, template);
        assertThat(original.status()).isEqualTo(201);
        var conflict = post("/api/runs", intent, key, token);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().path("code").stringValue()).isEqualTo("IDEMPOTENCY_CONFLICT");
        var replay = create(key, template);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(original.body());
    }

    @Test
    void separatesFailureAndCancelFromJudgingAndFiltersByOwner() throws Exception {
        String id = id(create(UUID.randomUUID().toString(), "{{requirement}}"));
        var failure = Map.of("status", "FAILED", "failureCode", "TIMEOUT");
        var result = post("/api/runs/" + id + "/result", failure, null, token);
        assertThat(result.body().path("status").stringValue()).isEqualTo("FAILED");
        assertThat(result.body().path("verdict").isNull()).isTrue();
        assertThat(post("/api/runs/" + id + "/result", failure, null, token).status()).isEqualTo(200);
        assertThat(complete(id, "{}").status()).isEqualTo(409);
        String canceled = id(create(UUID.randomUUID().toString(), "{{requirement}}"));
        assertThat(post("/api/runs/" + canceled + "/result", Map.of("status", "CANCELED"), null, token)
                .body().path("status").stringValue()).isEqualTo("CANCELED");
        var other = new PracticeRuns(jdbc, transactionManager, catalog, judge, "another-fixture", Clock.systemUTC());
        other.run(null);
        assertThatThrownBy(() -> other.get(id)).isInstanceOf(PracticeError.class).hasMessage("RESOURCE_NOT_FOUND");
        assertThatThrownBy(() -> other.submit(id, new PracticeRuns.Submission("CANCELED", null, null, Map.of())))
                .isInstanceOf(PracticeError.class).hasMessage("RESOURCE_NOT_FOUND");
    }

    @Test
    void concurrentCreatesAndDifferentResultsHaveOneWinner() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> create(key, "{{requirement}}"));
            var second = threads.submit(() -> create(key, "{{requirement}}"));
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertThat(new int[]{a.status(), b.status()}).containsExactlyInAnyOrder(201, 200);
            String id = id(a);
            assertThat(id(b)).isEqualTo(id);
            var x = threads.submit(() -> complete(id, "{}"));
            var y = threads.submit(() -> complete(id, "[]"));
            var c = x.get(5, TimeUnit.SECONDS);
            var d = y.get(5, TimeUnit.SECONDS);
            assertThat(new int[]{c.status(), d.status()}).containsExactlyInAnyOrder(200, 409);
            assertThat(get("/api/runs/" + id).body()).isEqualTo(c.status() == 200 ? c.body() : d.body());
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM prompt_snapshots s JOIN runs r ON s.id = r.snapshot_id WHERE r.idempotency_key = ?
                    """).param(key).query(Integer.class).single()).isEqualTo(1);
        }
    }

    @Test
    void rechecksExpiryAfterWaitingForSqliteWriteReservation() throws Exception {
        var clock = new ManualClock();
        var service = new PracticeRuns(jdbc, transactionManager, catalog, judge, "expiry-fixture", clock);
        service.run(null);
        String key = UUID.randomUUID().toString();
        var intent = new PracticeRuns.Create("002", 1, "case-1", "D0", Map.of("user", "{{requirement}}"));
        var created = service.create(key, intent).run();
        long expires = Instant.parse(created.expiresAt()).toEpochMilli();
        clock.now.set(expires - 100);
        clock.watch = true;
        var submit = new PracticeRuns.Submission("COMPLETED", "{}", null, Map.of());
        try (var connection = data.getConnection(); var statement = connection.createStatement();
                var threads = Executors.newSingleThreadExecutor()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                var outcome = threads.submit(() -> {
                    try {
                        service.submit(created.runId(), submit);
                        return "ACCEPTED";
                    } catch (PracticeError error) {
                        return error.code;
                    }
                });
                assertThat(clock.read.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150);
                assertThat(outcome.isDone()).isFalse();
                clock.now.set(expires);
                statement.execute("COMMIT");
                assertThat(outcome.get(2, TimeUnit.SECONDS)).isEqualTo("RUN_EXPIRED");
            } finally {
                // A test failure before COMMIT must release the manual transaction.
                try {
                    statement.execute("ROLLBACK");
                } catch (java.sql.SQLException ignored) {
                    // COMMIT already released it.
                }
            }
        }
        assertThat(service.get(created.runId()).status()).isEqualTo("EXPIRED");
        assertThat(service.create(key, intent).run().runId()).isEqualTo(created.runId());
        assertThatThrownBy(() -> service.submit(created.runId(), submit)).hasMessage("RUN_EXPIRED");
        assertThat(jdbc.sql("SELECT raw_output FROM runs WHERE id = ?").param(created.runId())
                .query((row, index) -> row.getString(1)).list()).containsExactly((String) null);
    }

    private Response complete(String id, String raw) throws Exception {
        return post("/api/runs/" + id + "/result", Map.of("status", "COMPLETED", "rawOutput", raw), null, token);
    }

    private Response create(String key, String prompt) throws Exception {
        return post("/api/runs", createBody(prompt), key, token);
    }

    private static Map<String, Object> createBody(String prompt) {
        return Map.of("problemId", "002", "version", 1, "caseId", "case-1", "mode", "D0",
                "slots", Map.of("user", prompt), "requestParams", Map.of());
    }

    private static String id(Response response) {
        assertThat(response.body().path("runId").isString()).as(response.body().toString()).isTrue();
        return response.body().path("runId").stringValue();
    }

    private Response get(String path) throws Exception {
        return send(request(path).GET().build());
    }

    private Response post(String path, Object body, String key, String csrf) throws Exception {
        var http = request(path).header("Content-Type", "application/json");
        if (key != null) http.header("Idempotency-Key", key);
        if (csrf != null) http.header("X-Practice-CSRF", csrf);
        return send(http.POST(HttpRequest.BodyPublishers.ofString(PracticeRuns.JSON.writeValueAsString(body))).build());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private Response send(HttpRequest request) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), PracticeRuns.JSON.readTree(response.body()),
                response.headers().firstValue("Location").orElse(null));
    }

    private record Response(int status, JsonNode body, String location) {
    }

    private static class ManualClock extends Clock {
        final AtomicLong now = new AtomicLong(1_000_000);
        final CountDownLatch read = new CountDownLatch(1);
        volatile boolean watch;

        @Override
        public long millis() {
            long value = now.get();
            if (watch) read.countDown();
            return value;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Expected UTC");
            return this;
        }
    }
}
