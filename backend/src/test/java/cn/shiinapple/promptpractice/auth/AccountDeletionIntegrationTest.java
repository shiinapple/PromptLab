package cn.shiinapple.promptpractice.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cn.shiinapple.promptpractice.PromptPracticeApplication;
import cn.shiinapple.promptpractice.practice.PracticeError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "prompt-practice.maintenance.account-deletion-interval=1h")
@DirtiesContext
class AccountDeletionIntegrationTest {
    private static final String PATH = "/api/account/deletion";
    private static final Instant REQUESTED = Instant.parse("2026-11-25T12:00:00Z");
    private static final Instant DEADLINE = REQUESTED.plus(Duration.ofDays(3));
    private static final List<String> OWNED = List.of("account_deletion_requests", "user_settings", "drafts",
            "prompt_snapshots", "runs", "submissions");

    @TempDir static Path directory;
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired Accounts accounts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("prompt-practice.database.path", () -> directory.resolve("deletion.sqlite3").toString());
    }

    @Test
    void allDeletionRoutesRequireTheOwnersSessionAndCsrf() throws Exception {
        var owner = login(createUser(accounts));
        var other = login(createUser(accounts));
        try (var http = HttpClient.newHttpClient()) {
            for (String verb : List.of("POST", "GET", "DELETE")) {
                assertEquals(401, send(http, verb, PATH, null, null, null).status);
                var missing = send(http, verb, PATH, owner.cookie, null, null);
                assertEquals(403, missing.status);
                assertEquals("CSRF_INVALID", missing.body.path("code").asText());
                assertEquals(403, send(http, verb, PATH, owner.cookie, other.csrf, null).status);
            }
            assertEquals(201, send(http, "POST", PATH, owner, null).status);
            var none = send(http, "GET", PATH, other, null);
            assertEquals(200, none.status);
            assertEquals(json.readTree("{\"status\":\"NONE\"}"), none.body);
            assertEquals(409, send(http, "DELETE", PATH, other, null).status);
            assertEquals(404, send(http, "GET", PATH + "/" + owner.user.id, other, null).status);
            assertEquals("PENDING", send(http, "GET", PATH, owner, null).body.path("status").asText());
            assertEquals(204, send(http, "DELETE", PATH, owner, null).status);
        }
    }

    @Test
    void applyingOnlyAddsOneRequestAndWithdrawalPreservesTheAccountAndItsData() throws Exception {
        var user = login(createUser(accounts));
        try (var http = HttpClient.newHttpClient()) {
            String run = populate(http, user);
            var first = send(http, "POST", PATH, user, null);
            assertEquals(201, first.status);
            assertEquals("PENDING", first.body.path("status").asText());
            var requested = Instant.parse(first.body.path("requestedAt").asText());
            var effective = Instant.parse(first.body.path("effectiveAt").asText());
            assertEquals(Duration.ofDays(3), Duration.between(requested, effective));
            var repeated = send(http, "POST", PATH, user, null);
            assertEquals(200, repeated.status);
            assertEquals(first.body, repeated.body);
            assertEquals(first.body, send(http, "GET", PATH, user, null).body);
            assertEquals(200, send(http, "GET", "/api/runs/" + run, user, null).status);
            assertEquals(200, send(http, "GET", "/api/me", user, null).status);
            assertOwnedRows(user.user.id, 1);

            assertEquals(204, send(http, "DELETE", PATH, user, null).status);
            assertEquals(409, send(http, "DELETE", PATH, user, null).status);
            assertEquals(0, at(effective).deleteDue());
            assertTrue(accounts.active(user.user.id).isPresent());
            assertEquals("NONE", send(http, "GET", PATH, user, null).body.path("status").asText());
            for (String table : OWNED.subList(1, OWNED.size())) assertEquals(1, count(table, user.user.id), table);
        }
    }

    @Test
    void permitsWithdrawalAtTwoDays23HoursAndDeletesAtExactlyThreeDays() throws Exception {
        var withdrawn = createUser(accounts);
        var due = login(createUser(accounts));
        var unaffected = login(createUser(accounts));
        try (var http = HttpClient.newHttpClient()) {
            String run = populate(http, due);
            String otherRun = populate(http, unaffected);
            at(REQUESTED).request(withdrawn.id);
            at(REQUESTED).request(due.user.id);
            String runPath = "/api/runs/" + run;
            assertEquals(200, send(http, "GET", runPath, due, null).status);
            var foreignRun = send(http, "GET", runPath, unaffected, null);
            assertEquals(404, foreignRun.status);
            assertEquals("RESOURCE_NOT_FOUND", foreignRun.body.path("code").asText());
            var foreignArchive = send(http, "POST", "/api/submissions", unaffected, Map.of("runId", run));
            assertEquals(404, foreignArchive.status);
            assertEquals("RESOURCE_NOT_FOUND", foreignArchive.body.path("code").asText());
            var withinGrace = at(REQUESTED.plus(Duration.ofHours(71)));
            withinGrace.withdraw(withdrawn.id);
            assertEquals(0, withinGrace.deleteDue());
            assertOwnedRows(due.user.id, 1);

            var atDeadline = at(DEADLINE);
            var conflict = assertThrows(PracticeError.class, () -> atDeadline.withdraw(due.user.id));
            assertEquals("NO_WITHDRAWABLE_DELETION_REQUEST", conflict.getMessage());
            var repeated = atDeadline.request(due.user.id);
            assertFalse(repeated.created());
            assertEquals(DEADLINE.toString(), repeated.status().effectiveAt());
            assertEquals(1, atDeadline.deleteDue());
            assertOwnedRows(due.user.id, 0);
            assertTrue(accounts.active(due.user.id).isEmpty());
            assertEquals(401, send(http, "GET", "/api/me", due, null).status);
            var anonymous = csrf(http, null);
            assertEquals(401, send(http, "POST", "/api/auth/login", anonymous.cookie, anonymous.csrf,
                    due.user.credentials()).status);
            assertEquals(404, send(http, "GET", runPath, unaffected, null).status);
            assertEquals(200, send(http, "GET", "/api/runs/" + otherRun, unaffected, null).status);
            for (String table : OWNED.subList(1, OWNED.size())) assertEquals(1, count(table, unaffected.user.id), table);
            assertTrue(accounts.active(withdrawn.id).isPresent());
        }
    }

    @Test
    void concurrentRequestsAndWithdrawalHaveOnlySerializedOutcomes() throws Exception {
        var user = login(createUser(accounts));
        try (var http = HttpClient.newHttpClient(); var threads = Executors.newFixedThreadPool(2)) {
            var applyTogether = new CyclicBarrier(2);
            var first = threads.submit(() -> {
                applyTogether.await(5, TimeUnit.SECONDS);
                return send(http, "POST", PATH, user, null);
            });
            applyTogether.await(5, TimeUnit.SECONDS);
            var second = send(http, "POST", PATH, user, null);
            var peer = first.get(5, TimeUnit.SECONDS);
            assertEquals(Set.of(201, 200), Set.of(second.status, peer.status));
            assertEquals(second.body, peer.body);
            assertEquals(1, count("account_deletion_requests", user.user.id));

            var applyAndWithdraw = new CyclicBarrier(2);
            var apply = threads.submit(() -> {
                applyAndWithdraw.await(5, TimeUnit.SECONDS);
                return send(http, "POST", PATH, user, null);
            });
            applyAndWithdraw.await(5, TimeUnit.SECONDS);
            assertEquals(204, send(http, "DELETE", PATH, user, null).status);
            var result = apply.get(5, TimeUnit.SECONDS);
            var current = send(http, "GET", PATH, user, null).body;
            if (result.status == 201) {
                assertEquals(result.body, current);
                assertEquals(1, count("account_deletion_requests", user.user.id));
                assertEquals(204, send(http, "DELETE", PATH, user, null).status);
            } else {
                assertEquals(200, result.status);
                assertEquals(second.body, result.body);
                assertEquals("NONE", current.path("status").asText());
                assertEquals(0, count("account_deletion_requests", user.user.id));
            }
        }
    }

    @Test
    void failedCascadeRollsBackAndTheNextSweepCanRetry() throws Exception {
        var user = login(createUser(accounts));
        try (var http = HttpClient.newHttpClient()) {
            populate(http, user);
            at(REQUESTED).request(user.user.id);
            jdbc.sql("""
                    CREATE TRIGGER fail_after_cascade AFTER DELETE ON users
                    BEGIN SELECT RAISE(FAIL, 'simulated deletion failure'); END
                    """).update();
            var due = at(DEADLINE);
            try {
                assertThrows(DataAccessException.class, due::deleteDue);
                assertTrue(accounts.active(user.user.id).isPresent());
                assertOwnedRows(user.user.id, 1);
            } finally {
                jdbc.sql("DROP TRIGGER fail_after_cascade").update();
            }
            assertEquals(1, due.deleteDue());
            assertOwnedRows(user.user.id, 0);
            assertEquals(0, due.deleteDue());
        }
    }

    @Test
    void restartedSchedulerDeletesOnlyPersistedDueRequests() throws Exception {
        Path db = directory.resolve("scheduler-restart.sqlite3");
        User due;
        User future;
        User withdrawn;
        try (var context = start(db, "1h")) {
            var users = context.getBean(Accounts.class);
            var dbClient = context.getBean(JdbcClient.class);
            var writer = context.getBean(PlatformTransactionManager.class);
            due = createUser(users);
            future = createUser(users);
            withdrawn = createUser(users);
            var past = new AccountDeletion(dbClient, writer, users,
                    Clock.fixed(Instant.now().minus(Duration.ofDays(4)), ZoneOffset.UTC));
            past.request(due.id);
            var deletions = context.getBean(AccountDeletion.class);
            deletions.request(future.id);
            deletions.request(withdrawn.id);
            deletions.withdraw(withdrawn.id);
            assertTrue(users.active(due.id).isPresent());
        }
        try (var context = start(db, "100ms")) {
            var users = context.getBean(Accounts.class);
            long timeout = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (users.active(due.id).isPresent() && System.nanoTime() < timeout) Thread.sleep(50);
            assertTrue(users.active(due.id).isEmpty());
            assertTrue(users.active(future.id).isPresent());
            assertTrue(users.active(withdrawn.id).isPresent());
            assertEquals(List.of(future.id), context.getBean(JdbcClient.class)
                    .sql("SELECT user_id FROM account_deletion_requests").query(String.class).list());
        }
    }

    private AccountDeletion at(Instant when) {
        return new AccountDeletion(jdbc, transactions, accounts, Clock.fixed(when, ZoneOffset.UTC));
    }

    private static ConfigurableApplicationContext start(Path file, String interval) {
        return new SpringApplicationBuilder(PromptPracticeApplication.class).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.main.banner-mode=off", "--prompt-practice.database.path=" + file,
                        "--prompt-practice.maintenance.account-deletion-interval=" + interval);
    }

    private void assertOwnedRows(String userId, int count) {
        for (String table : OWNED) assertEquals(count, count(table, userId), table);
    }

    private int count(String table, String userId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?").param(userId).query(Integer.class).single();
    }

    private String populate(HttpClient http, Session session) throws Exception {
        assertEquals(200, send(http, "PATCH", "/api/settings", session, Map.of("locale", "en")).status);
        var slots = Map.of("user", "{{requirement}}");
        assertEquals(201, send(http, "PUT", "/api/problems/002/draft", session,
                Map.of("version", 2, "revision", 0, "caseId", "case-1", "slots", slots)).status);
        var created = send(http, "POST", "/api/runs", session, Map.of("problemId", "002", "version", 2,
                "caseId", "case-1", "mode", "D0", "slots", slots));
        assertEquals(201, created.status);
        String run = created.body.path("runId").asText();
        assertEquals(200, send(http, "POST", "/api/runs/" + run + "/result", session,
                Map.of("status", "COMPLETED", "rawOutput", "{}")).status);
        assertEquals(201, send(http, "POST", "/api/submissions", session, Map.of("runId", run)).status);
        return run;
    }

    private static User createUser(Accounts accounts) {
        String email = UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        return new User(accounts.create(email, password), email, password);
    }

    private Session login(User user) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var before = csrf(http, null);
            var login = send(http, "POST", "/api/auth/login", before.cookie, before.csrf, user.credentials());
            assertEquals(204, login.status);
            var authenticated = csrf(http, login.cookie);
            return new Session(user, login.cookie, authenticated.csrf);
        }
    }

    private Session csrf(HttpClient http, String cookie) throws Exception {
        var response = send(http, "GET", "/api/auth/csrf", cookie, null, null);
        assertEquals(200, response.status);
        return new Session(null, cookie == null ? response.cookie : cookie, response.body.path("token").asText());
    }

    private Reply send(HttpClient http, String method, String path, Session session, Object data) throws Exception {
        return send(http, method, path, session.cookie, session.csrf, data);
    }

    private Reply send(HttpClient http, String method, String path, String cookie, String csrf, Object data) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header(Sessions.HEADER, csrf);
        if (data != null) request.header("Content-Type", "application/json");
        if (path.equals("/api/runs") && method.equals("POST")) request.header("Idempotency-Key", UUID.randomUUID().toString());
        var response = http.send(request.method(method, data == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), json.readTree(response.body()),
                response.headers().firstValue("Set-Cookie").map(header -> header.split(";", 2)[0]).orElse(null));
    }

    private record User(String id, String email, String password) {
        Map<String, String> credentials() { return Map.of("email", email, "password", password); }
    }

    private record Session(User user, String cookie, String csrf) {}
    private record Reply(int status, JsonNode body, String cookie) {}
}
