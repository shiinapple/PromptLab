package cn.shiinapple.promptpractice.auth;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import cn.shiinapple.promptpractice.PromptPracticeApplication;
import cn.shiinapple.promptpractice.judge.JudgeService;
import cn.shiinapple.promptpractice.maintenance.RunMaintenance;
import cn.shiinapple.promptpractice.ops.Operations;
import com.sun.net.httpserver.HttpServer;
import cn.shiinapple.promptpractice.practice.PracticeError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class M4IntegrationTest {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("prompt-practice.database.path", () -> directory.resolve("m4.sqlite3").toString());
        registry.add("prompt-practice.retention.backup", () -> "30d");
    }

    @LocalServerPort int port;
    @Autowired Accounts accounts;
    @Autowired RunMaintenance maintenance;
    @Autowired Operations operations;
    @Autowired Sessions sessions;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource database;
    @MockitoSpyBean JudgeService judge;
    @Autowired ObjectMapper json;

    @Test
    void normalizesUniqueEmail() {
        var user = createUser();
        var error = assertThrows(PracticeError.class,
                () -> accounts.create(" " + user.email.toUpperCase(Locale.ROOT) + " ", UUID.randomUUID().toString()));
        assertEquals("EMAIL_ALREADY_EXISTS", error.getMessage());
        assertEquals(user.email, jdbc.sql("SELECT email_normalized FROM users WHERE id = ?")
                .param(user.id).query(String.class).single());
    }

    @Test
    void rotatesSessionAndCsrfAndInvalidatesOnLogout() throws Exception {
        assertEquals(401, send("GET", "/api/me", null, null, null).status);
        assertEquals(401, send("GET", "/api/problems", null, null, null).status);
        var user = createUser();
        var anonymous = csrf(null);
        var withoutToken = send("POST", "/api/auth/login", anonymous.cookie, null, user.credentials());
        assertEquals(403, withoutToken.status);
        var login = send("POST", "/api/auth/login", anonymous.cookie, anonymous.token, user.credentials());
        assertEquals(204, login.status);
        String cookieAttributes = login.setCookie.toLowerCase(Locale.ROOT);
        assertTrue(cookieAttributes.contains("; secure"), "Session cookie must be secure");
        assertTrue(cookieAttributes.contains("; httponly"), "Session cookie must be HttpOnly");
        assertTrue(cookieAttributes.contains("; samesite=lax"), "Session cookie must use SameSite=Lax");
        assertTrue(!anonymous.cookie.equals(login.cookie()), "Login must rotate the session identifier");
        assertEquals(401, send("GET", "/api/me", anonymous.cookie, null, null).status);
        assertEquals(user.id, send("GET", "/api/me", login.cookie(), null, null).body.path("userId").asText());
        assertEquals(200, send("GET", "/api/problems", login.cookie(), null, null).status);
        assertEquals(403, send("POST", "/api/auth/logout", login.cookie(), anonymous.token, null).status);
        assertEquals(204, send("POST", "/api/auth/logout", login.cookie(), csrf(login.cookie()).token, null).status);
        assertEquals(401, send("GET", "/api/me", login.cookie(), null, null).status);
    }

    @Test
    void rejectsUnknownDisabledAndWrongPasswordAndLimitsRepeatedAttempts() throws Exception {
        var disabled = createUser();
        var enabled = createUser();
        jdbc.sql("UPDATE users SET state = 'DISABLED' WHERE id = ?").param(disabled.id).update();
        var anonymous = csrf(null);
        assertCredentialFailure(anonymous, disabled.credentials());
        assertCredentialFailure(anonymous, Map.of("email", UUID.randomUUID() + "@example.test",
                "password", UUID.randomUUID().toString()));
        var wrong = Map.of("email", enabled.email, "password", UUID.randomUUID().toString());
        for (int attempt = 0; attempt < 8; attempt++) assertCredentialFailure(anonymous, wrong);
        assertEquals(429, send("POST", "/api/auth/login", anonymous.cookie, anonymous.token, wrong).status);
    }

    @Test
    void settingsPatchMergesAllowedFieldsAndKeepsAccountsSeparate() throws Exception {
        var a = login(createUser());
        var b = login(createUser());
        assertEquals("zh-CN", get("/api/settings", a).body.path("locale").asText());
        assertEquals(403, send("PATCH", "/api/settings", a.cookie, null, Map.of("locale", "en")).status);
        assertEquals(400, write("PATCH", "/api/settings", a, Map.of("editorPrefs", Map.of("secret", "no"))).status);
        assertEquals(400, write("PATCH", "/api/settings", a, Map.of("locale", "fr")).status);
        assertEquals(400, write("PATCH", "/api/settings", a, Map.of("editorPrefs", Map.of("wordWrap", "true"))).status);
        var saved = write("PATCH", "/api/settings", a, Map.of("locale", "en", "editorPrefs", Map.of("fontSize", 18)));
        assertEquals(200, saved.status);
        assertEquals("en", saved.body.path("locale").asText());
        assertEquals(18, saved.body.path("editorPrefs").path("fontSize").asInt());
        var merged = write("PATCH", "/api/settings", a, Map.of("editorPrefs", Map.of("wordWrap", true)));
        assertEquals(18, merged.body.path("editorPrefs").path("fontSize").asInt());
        assertTrue(merged.body.path("editorPrefs").path("wordWrap").asBoolean());
        assertEquals("zh-CN", get("/api/settings", b).body.path("locale").asText());
        assertEquals(404, get("/api/users/" + get("/api/me", a).body.path("userId").asText() + "/settings", b).status);
    }

    @Test
    void draftsPersistPerOwnerAndVersionAndCanBeCopiedWithPut() throws Exception {
        var user = createUser();
        var a = login(user);
        var b = login(createUser());
        String path = "/api/problems/002/draft";
        assertTrue(get(path, a).body.path("draft").isNull());
        assertEquals(201, write("PUT", path, a, draftBody(1, 0, "old text")).status);
        assertTrue(get(path, a).body.path("draft").isNull());
        var old = get(path + "?version=1", a).body.path("draft");
        assertEquals("old text", old.path("slots").path("user").asText());
        assertEquals(201, write("PUT", path, a, draftBody(2, 0, old.path("slots").path("user").asText())).status);
        assertEquals(200, write("PUT", path, a, draftBody(2, 1, "")).status);
        var conflict = write("PUT", path, a, draftBody(2, 1, "stale"));
        assertEquals(409, conflict.status);
        assertEquals(2, conflict.body.path("details").path("serverRevision").asLong());
        assertEquals(2, get(path, login(user)).body.path("draft").path("revision").asLong());
        assertTrue(get(path + "?version=1", b).body.path("draft").isNull());
        assertEquals(409, write("PUT", path, b, draftBody(2, 2, "attempt")).status);
        assertEquals(201, write("PUT", path, b, draftBody(2, 0, "other owner's text")).status);
        assertEquals("", get(path, a).body.path("draft").path("slots").path("user").asText());
        assertEquals("old text", get(path + "?version=1", a).body.path("draft").path("slots").path("user").asText());
    }

    @Test
    void onlyOneConcurrentDraftWriterWithTheSameRevisionSucceeds() throws Exception {
        var session = login(createUser());
        String path = "/api/problems/002/draft";
        assertEquals(201, write("PUT", path, session, draftBody(1, 0, "initial")).status);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> write("PUT", path, session, draftBody(1, 1, "one")));
            var two = workers.submit(() -> write("PUT", path, session, draftBody(1, 1, "two")));
            var left = one.get(6, TimeUnit.SECONDS);
            var right = two.get(6, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of(200, 409), java.util.Set.of(left.status, right.status));
            assertEquals(2, (left.status == 409 ? left : right).body.path("details").path("serverRevision").asLong());
        }
    }

    @Test
    void archivesOnlyOwnCompletedRunsIdempotentlyAndDeletesOnlyTheMarker() throws Exception {
        var a = login(createUser());
        var b = login(createUser());
        String run = newRun(a);
        assertEquals(409, write("POST", "/api/submissions", a, Map.of("runId", run)).status);
        assertEquals(404, write("POST", "/api/submissions", b, Map.of("runId", run)).status);
        assertEquals(404, get("/api/runs/" + run, b).status);
        assertEquals(200, complete(a, run).status);
        clearInvocations(judge);
        var archived = write("POST", "/api/submissions", a, Map.of("runId", run));
        assertEquals(201, archived.status);
        var retry = write("POST", "/api/submissions", a, Map.of("runId", run));
        assertEquals(200, retry.status);
        assertEquals(archived.body, retry.body);
        String id = archived.body.path("submissionId").asText();
        var item = get("/api/submissions", a).body.path("items").path(0);
        assertEquals(id, item.path("submissionId").asText());
        assertFalse(item.has("rawOutput"));
        assertFalse(item.has("messages"));
        assertEquals(0, get("/api/submissions", b).body.path("items").size());
        assertEquals(404, write("DELETE", "/api/submissions/" + id, b, null).status);
        assertEquals(204, write("DELETE", "/api/submissions/" + id, a, null).status);
        assertEquals(0, get("/api/submissions", a).body.path("items").size());
        assertEquals("{}", get("/api/runs/" + run, a).body.path("rawOutput").asText());
        verifyNoInteractions(judge);
    }

    @Test
    void paginatesByTimestampAndIdAndRejectsForeignOrMismatchedCursors() throws Exception {
        var a = login(createUser());
        var b = login(createUser());
        String older = newRun(a);
        String newer = newRun(a);
        String latest = newRun(a);
        newRun(b);
        jdbc.sql("UPDATE runs SET created_at = 1000 WHERE id IN (?, ?, ?)").params(older, newer, latest).update();
        jdbc.sql("UPDATE prompt_snapshots SET version = 1 WHERE id = (SELECT snapshot_id FROM runs WHERE id = ?)")
                .param(older).update();
        for (String run : java.util.List.of(older, newer, latest)) {
            assertEquals(200, complete(a, run).status);
            assertEquals(201, write("POST", "/api/submissions", a, Map.of("runId", run)).status);
        }
        var expected = new HashSet<>(java.util.List.of(older, newer, latest));
        for (String collection : java.util.List.of("/api/problems/002/runs", "/api/submissions")) {
            String path = collection + "?limit=1";
            var ids = new HashSet<String>();
            var versions = new HashSet<Integer>();
            var page = get(path, a);
            assertEquals(200, page.status);
            String cursor = page.body.path("nextCursor").asText();
            assertFalse(cursor.isBlank());
            assertEquals(400, get(path + "&cursor=" + cursor, b).status);
            assertEquals(400, get("/api/problems/001/runs?cursor=" + cursor, a).status);
            assertEquals(400, get(path + "&cursor=tampered", a).status);
            if (collection.endsWith("/runs")) {
                assertEquals(400, get("/api/submissions?cursor=" + cursor, a).status);
                newRun(a);
            }
            for (int index = 0; index < 3; index++) {
                var items = page.body.path("items");
                assertEquals(1, items.size());
                assertFalse(items.path(0).has("rawOutput"));
                assertFalse(items.path(0).has("messages"));
                assertTrue(ids.add(items.path(0).path("runId").asText()));
                versions.add(items.path(0).path("version").asInt());
                if (index < 2) page = get(path + "&cursor=" + page.body.path("nextCursor").asText(), a);
            }
            assertTrue(page.body.path("nextCursor").isNull());
            assertEquals(expected, ids);
            assertEquals(java.util.Set.of(1, 2), versions);
        }
    }

    @Test
    void mapsArchiveBusyTimeoutTo503AndAllowsRetry() throws Exception {
        var session = login(createUser());
        String run = newRun(session);
        assertEquals(200, complete(session, run).status);
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                var result = write("POST", "/api/submissions", session, Map.of("runId", run));
                assertEquals(503, result.status);
                assertEquals("TEMPORARILY_UNAVAILABLE", result.body.path("code").asText());
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertEquals(201, write("POST", "/api/submissions", session, Map.of("runId", run)).status);
        assertEquals(201, write("PUT", "/api/problems/002/draft", session, draftBody(2, 0, "after busy")).status);
        assertEquals(409, write("PUT", "/api/problems/002/draft", session, draftBody(2, 0, "conflict after busy")).status);
    }

    @Test
    void expiresPendingAtItsDeadlineAndRejectsLateResults() throws Exception {
        var session = login(createUser());
        String run = newRun(session, "DIRECT");
        var before = get("/api/runs/" + run, session).body;
        assertEquals(Duration.ofMinutes(10), Duration.between(Instant.parse(before.path("createdAt").asText()),
                Instant.parse(before.path("expiresAt").asText())));
        long now = System.currentTimeMillis();
        jdbc.sql("UPDATE runs SET created_at = ?, expires_at = ? WHERE id = ?")
                .params(now - 2000, now - 1000, run).update();
        assertEquals(1, maintenance.sweep().expired());
        var expired = get("/api/runs/" + run, session).body;
        assertEquals("EXPIRED", expired.path("status").asText());
        assertEquals(Instant.ofEpochMilli(now - 1000).toString(), expired.path("finishedAt").asText());
        assertEquals("EXPIRED", jdbc.sql("SELECT status FROM runs WHERE id = ?").param(run).query(String.class).single());
        assertEquals(410, complete(session, run).status);
    }

    @Test
    void removesOldUnarchivedSnapshotsAndReconsidersUnarchivedRunsOnNextSweep() throws Exception {
        var session = login(createUser());
        String archived = newRun(session);
        String expired = newRun(session, "DIRECT");
        assertEquals(200, complete(session, archived).status);
        String archiveId = write("POST", "/api/submissions", session, Map.of("runId", archived))
                .body.path("submissionId").asText();
        ageTerminal(archived, Duration.ofDays(31));
        long now = System.currentTimeMillis();
        long oldDeadline = now - Duration.ofDays(31).toMillis();
        jdbc.sql("UPDATE runs SET created_at = ?, expires_at = ? WHERE id = ?")
                .params(oldDeadline - Duration.ofMinutes(10).toMillis(), oldDeadline, expired).update();
        var sweep = maintenance.sweep();
        assertEquals(1, sweep.expired());
        assertEquals(1, sweep.deletedSnapshots());
        assertEquals(200, get("/api/runs/" + archived, session).status);
        assertEquals(404, get("/api/runs/" + expired, session).status);
        assertEquals(204, write("DELETE", "/api/submissions/" + archiveId, session, null).status);
        assertEquals(200, get("/api/runs/" + archived, session).status);
        assertEquals(1, maintenance.sweep().deletedSnapshots());
        assertEquals(404, get("/api/runs/" + archived, session).status);
    }

    @Test
    void cleanupAndArchiveRaceLeavesEitherBothRecordsOrNeither() throws Exception {
        var session = login(createUser());
        for (int attempt = 0; attempt < 4; attempt++) {
            String run = newRun(session);
            assertEquals(200, complete(session, run).status);
            String snapshot = jdbc.sql("SELECT snapshot_id FROM runs WHERE id = ?").param(run).query(String.class).single();
            ageTerminal(run, Duration.ofDays(31));
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var archive = workers.submit(() -> {
                    start.await();
                    return write("POST", "/api/submissions", session, Map.of("runId", run));
                });
                var cleanup = workers.submit(() -> {
                    start.await();
                    return maintenance.sweep();
                });
                start.countDown();
                int status = archive.get(6, TimeUnit.SECONDS).status;
                cleanup.get(6, TimeUnit.SECONDS);
                assertTrue(status == 201 || status == 404);
                int count = status == 201 ? 1 : 0;
                assertEquals(count, jdbc.sql("SELECT count(*) FROM runs WHERE id = ?").param(run).query(Integer.class).single());
                assertEquals(count, jdbc.sql("SELECT count(*) FROM submissions WHERE run_id = ?").param(run).query(Integer.class).single());
                assertEquals(count, jdbc.sql("SELECT count(*) FROM prompt_snapshots WHERE id = ?")
                        .param(snapshot).query(Integer.class).single());
            }
        }
    }

    @Test
    void disablingRevokesEverySessionAndDeletingCascadesOnlyThatAccountsData() throws Exception {
        var user = createUser();
        var a = login(user);
        var b = login(user);
        var third = login(user);
        var other = login(createUser());
        assertEquals(201, write("PUT", "/api/problems/002/draft", a, draftBody(2, 0, "private")).status);
        assertEquals(200, write("PATCH", "/api/settings", a, Map.of("locale", "en")).status);
        String run = newRun(a);
        assertEquals(200, complete(a, run).status);
        assertEquals(201, write("POST", "/api/submissions", a, Map.of("runId", run)).status);
        assertEquals(1, operations.execute(new String[]{"delete-user", user.email}));
        assertEquals(0, operations.execute(new String[]{"disable-user", user.email}));
        assertEquals(401, get("/api/settings", a).status);
        assertTrue(!b.token.equals(csrf(b.cookie).token), "Every existing session must be invalidated");
        sessions.revokeInactive();
        assertEquals(401, get("/api/me", third).status);
        assertEquals(200, get("/api/me", other).status);
        assertEquals(0, operations.execute(new String[]{"delete-user", user.email}));
        for (String table : java.util.List.of("drafts", "user_settings", "prompt_snapshots", "runs", "submissions")) {
            assertEquals(0, jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?")
                    .param(user.id).query(Integer.class).single());
        }
        assertEquals(0, jdbc.sql("SELECT count(*) FROM users WHERE id = ?").param(user.id).query(Integer.class).single());
        assertEquals(200, get("/api/me", other).status);
    }

    @Test
    void backsUpAndRestoresAnIndependentDatabaseWithUsableBusinessRows() throws Exception {
        var user = createUser();
        var session = login(user);
        assertEquals(201, write("PUT", "/api/problems/002/draft", session, draftBody(1, 0, "saved content")).status);
        assertEquals(200, write("PATCH", "/api/settings", session, Map.of("locale", "en")).status);
        String run = newRun(session);
        assertEquals(200, complete(session, run).status);
        assertEquals(201, write("POST", "/api/submissions", session, Map.of("runId", run)).status);
        Path backups = directory.resolve("backups");
        Files.createDirectory(backups, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path expired = Files.createFile(backups.resolve("prompt-practice-1-" + UUID.randomUUID() + ".sqlite3"));
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Path unrelated = Files.createFile(backups.resolve("preserve.sqlite3"));
        Files.setLastModifiedTime(unrelated, Files.getLastModifiedTime(expired));
        assertEquals(0, operations.execute(new String[]{"backup", backups.toString()}));
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(unrelated));
        Path backup;
        try (var files = Files.list(backups)) {
            backup = files.filter(file -> file.getFileName().toString().startsWith("prompt-practice-")).findFirst().orElseThrow();
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(backup)));
        Path restore = directory.resolve("isolated restore");
        assertEquals(1, Operations.restore(new String[]{"restore", directory.resolve("m4.sqlite3").toString(),
                directory.resolve("must not restore live database").toString()}));
        assertEquals(0, Operations.restore(new String[]{"restore", backup.toString(), restore.toString()}));
        assertEquals(1, Operations.restore(new String[]{"restore", backup.toString(), restore.toString()}));
        try (var context = new SpringApplicationBuilder(PromptPracticeApplication.class).web(WebApplicationType.NONE)
                .run("--prompt-practice.database.path=" + restore.resolve("prompt-practice.sqlite3"))) {
            var restored = context.getBean(JdbcClient.class);
            assertEquals("en", restored.sql("SELECT locale FROM user_settings WHERE user_id = ?")
                    .param(user.id).query(String.class).single());
            assertEquals("saved content", restored.sql("SELECT json_extract(slots_json, '$.user') FROM drafts WHERE user_id = ?")
                    .param(user.id).query(String.class).single());
            assertEquals("{}", restored.sql("""
                    SELECT r.raw_output FROM runs r JOIN submissions a ON r.id = a.run_id AND r.user_id = a.user_id
                    WHERE r.id = ? AND r.user_id = ?
                    """).params(run, user.id).query(String.class).single());
        }
    }

    @Test
    void directMockIsCalledOnceAndNeverAgainForArchiveOrHistory() throws Exception {
        var session = login(createUser());
        var calls = new AtomicInteger();
        var mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/model", exchange -> {
            calls.incrementAndGet();
            byte[] output = "{}".getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.sendResponseHeaders(200, output.length);
                exchange.getResponseBody().write(output);
            }
        });
        mock.start();
        try (var client = HttpClient.newHttpClient()) {
            String run = newRun(session, "DIRECT");
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/model"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(200, write("POST", "/api/runs/" + run + "/result", session,
                    Map.of("status", "COMPLETED", "rawOutput", response.body())).status);
            int beforeArchive = calls.get();
            assertEquals(1, beforeArchive);
            assertEquals(201, write("POST", "/api/submissions", session, Map.of("runId", run)).status);
            assertEquals(200, write("POST", "/api/submissions", session, Map.of("runId", run)).status);
            assertEquals(200, get("/api/submissions", session).status);
            assertEquals(200, get("/api/problems/002/runs", session).status);
            assertEquals(0, calls.get() - beforeArchive);
        } finally {
            mock.stop(0);
        }
    }

    @Test
    void retentionStartsAtTerminalTimeAndLeavesDraftsAlone() throws Exception {
        var user = createUser();
        var session = login(user);
        String draft = "/api/problems/002/draft";
        assertEquals(201, write("PUT", draft, session, draftBody(2, 0, "long-lived")).status);
        jdbc.sql("UPDATE drafts SET updated_at = 1 WHERE user_id = ?").param(user.id).update();
        String recentResult = newRun(session);
        assertEquals(200, complete(session, recentResult).status);
        jdbc.sql("UPDATE runs SET created_at = 1 WHERE id = ?").param(recentResult).update();

        assertEquals(0, maintenance.sweep().deletedSnapshots());
        assertEquals(200, get("/api/runs/" + recentResult, session).status);
        ageTerminal(recentResult, Duration.ofDays(31));
        assertEquals(1, maintenance.sweep().deletedSnapshots());
        assertEquals(404, get("/api/runs/" + recentResult, session).status);
        assertEquals("long-lived", get(draft, session).body.at("/draft/slots/user").asText());
    }

    private void ageTerminal(String run, Duration age) {
        long finished = System.currentTimeMillis() - age.toMillis();
        jdbc.sql("UPDATE runs SET created_at = ?, finished_at = ? WHERE id = ?")
                .params(finished - 1000, finished, run).update();
        jdbc.sql("UPDATE prompt_snapshots SET created_at = ? WHERE id = (SELECT snapshot_id FROM runs WHERE id = ?)")
                .params(finished - 1000, run).update();
    }

    private String newRun(Session session) throws Exception {
        return newRun(session, "D0");
    }

    private String newRun(Session session, String mode) throws Exception {
        var body = Map.of("problemId", "002", "version", 2, "caseId", "case-1", "mode", mode,
                "slots", Map.of("user", "{{requirement}}"));
        var reply = send("POST", "/api/runs", session.cookie, session.token, body,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, reply.status);
        return reply.body.path("runId").asText();
    }

    private Reply complete(Session session, String runId) throws Exception {
        return write("POST", "/api/runs/" + runId + "/result", session, Map.of("status", "COMPLETED", "rawOutput", "{}"));
    }

    private Session login(User user) throws Exception {
        var anonymous = csrf(null);
        var reply = send("POST", "/api/auth/login", anonymous.cookie, anonymous.token, user.credentials());
        assertEquals(204, reply.status);
        return csrf(reply.cookie());
    }

    private Reply get(String path, Session session) throws Exception {
        return send("GET", path, session.cookie, null, null);
    }

    private Reply write(String method, String path, Session session, Object body) throws Exception {
        return send(method, path, session.cookie, session.token, body);
    }

    private Map<String, Object> draftBody(int version, long revision, String text) {
        return Map.of("version", version, "revision", revision, "caseId", "case-1", "slots", Map.of("user", text));
    }

    private void assertCredentialFailure(Session session, Map<String, String> body) throws Exception {
        var response = send("POST", "/api/auth/login", session.cookie, session.token, body);
        assertEquals(401, response.status);
        assertEquals("INVALID_CREDENTIALS", response.body.path("code").asText());
    }

    private User createUser() {
        String email = UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        return new User(accounts.create(email, password), email, password);
    }

    private Session csrf(String cookie) throws Exception {
        var response = send("GET", "/api/auth/csrf", cookie, null, null);
        assertEquals(200, response.status);
        return new Session(cookie == null ? response.cookie() : cookie, response.body.path("token").asText());
    }

    private Reply send(String method, String path, String cookie, String token, Object body) throws Exception {
        return send(method, path, cookie, token, body, Map.of());
    }

    private Reply send(String method, String path, String cookie, String token, Object body,
            Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(6));
        if (cookie != null) request.header("Cookie", cookie);
        if (token != null) request.header(Sessions.HEADER, token);
        if (body != null) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), json.readTree(response.body()),
                    response.headers().firstValue("Set-Cookie").orElse(""));
        }
    }

    private record User(String id, String email, String password) {
        @Override
        public String toString() {
            return "User[redacted]";
        }

        Map<String, String> credentials() {
            return Map.of("email", email, "password", password);
        }
    }

    private record Session(String cookie, String token) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }

    private record Reply(int status, JsonNode body, String setCookie) {
        @Override
        public String toString() {
            return "Reply[status=" + status + "]";
        }

        String cookie() {
            assertTrue(!setCookie.isEmpty(), "Expected session cookie");
            return setCookie.split(";", 2)[0];
        }
    }
}
