package cn.shiinapple.promptpractice.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cn.shiinapple.promptpractice.practice.PracticeError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class M4IntegrationTest {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("prompt-practice.database.path", () -> directory.resolve("m4.sqlite3").toString());
    }

    @LocalServerPort int port;
    @Autowired Accounts accounts;
    @Autowired JdbcClient jdbc;
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
        Map<String, String> credentials() {
            return Map.of("email", email, "password", password);
        }
    }

    private record Session(String cookie, String token) {}

    private record Reply(int status, JsonNode body, String setCookie) {
        String cookie() {
            assertTrue(!setCookie.isEmpty(), "Expected session cookie");
            return setCookie.split(";", 2)[0];
        }
    }
}
