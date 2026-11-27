package cn.shiinapple.promptpractice.practice;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import cn.shiinapple.promptpractice.auth.Sessions;
import cn.shiinapple.promptpractice.judge.JudgeService;
import org.springframework.core.env.Environment;

import static cn.shiinapple.promptpractice.practice.ApiInput.*;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;

@RestController
public class PracticeController {
    private static final String CSRF = "X-Practice-CSRF";
    private static final int MAX_SLOT_BYTES = 8 * 1024;
    private static final int MAX_CREATE_BYTES = MAX_SLOT_BYTES * 6 + 4096;
    private static final int MAX_RESULT_BYTES = JudgeService.MAX_OUTPUT_BYTES * 6 + 4096;
    private static final Set<String> FAILURES = Set.of("TIMEOUT", "UPSTREAM_ERROR", "TRUNCATED", "PROTOCOL_ERROR");
    private final PracticeRuns runs;
    private final ProblemCatalog catalog;
    private final boolean localProfile;
    private final String csrf = UUID.randomUUID().toString();

    PracticeController(PracticeRuns runs, ProblemCatalog catalog, Environment environment) {
        this.runs = runs;
        this.catalog = catalog;
        this.localProfile = environment.matchesProfiles("local-practice");
    }

    @GetMapping("/api/practice/session")
    ResponseEntity<?> session(HttpServletRequest request) throws IOException {
        if (!localProfile) throw new PracticeError(404, "RESOURCE_NOT_FOUND");
        local(request);
        return ok(Map.of("token", csrf, "headerName", CSRF));
    }

    @GetMapping("/api/problems")
    ResponseEntity<?> problems(HttpServletRequest request, @RequestParam(defaultValue = "zh-CN") String locale) throws IOException {
        owner(request);
        var items = catalog.loadPublished().stream().map(pack -> Map.of(
                "problemId", pack.problemId(), "version", pack.version(), "title", localized(pack, locale).path("title"),
                "category", pack.problem().path("category"), "difficulty", pack.problem().path("difficulty"))).toList();
        return ok(Map.of("items", items));
    }

    @GetMapping("/api/problems/{id}")
    ResponseEntity<?> problem(HttpServletRequest request, @PathVariable String id,
            @RequestParam(defaultValue = "zh-CN") String locale,
            @RequestParam(name = "version", required = false) String version) throws IOException {
        owner(request);
        var published = catalog.readPublished(id, problemVersion(version));
        var pack = published.pack();
        var text = localized(pack, locale);
        return ok(Map.of(
                "problemId", pack.problemId(), "version", pack.version(), "publishedVersion", published.publishedVersion(),
                "locale", locale,
                "title", text.path("title"), "description", text.path("description"),
                "defaultSlots", text.path("defaultSlots"), "slots", pack.problem().path("slots"),
                "messages", pack.problem().path("messages"),
                "cases", pack.cases().stream().map(item -> Map.of(
                        "caseId", item.caseId(), "inputLocale", item.definition().path("inputLocale"),
                        "variables", item.variables(),
                        "grading", item.assertions().isEmpty() ? "STRUCTURE_ONLY" : "RULES")).toList()));
    }

    @PostMapping(path = "/api/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(HttpServletRequest request) throws IOException {
        String owner = writeOwner(request);
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new PracticeError(400, "INVALID_IDEMPOTENCY_KEY");
        }
        JsonNode body = read(request, MAX_CREATE_BYTES);
        fields(body, Set.of("problemId", "version", "caseId", "mode", "slots", "requestParams"));
        String id = string(body.path("problemId"), 32);
        JsonNode version = body.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1) {
            throw new PracticeError(400, "INVALID_REQUEST");
        }
        String caseId = string(body.path("caseId"), 64);
        String mode = string(body.path("mode"), 16);
        if (!Set.of("D0", "DIRECT").contains(mode)) throw new PracticeError(422, "INVALID_MODE");
        if (body.has("requestParams")) fields(body.path("requestParams"), Set.of());
        JsonNode inputSlots = body.path("slots");
        if (!inputSlots.isObject()) throw new PracticeError(400, "INVALID_REQUEST");
        var slots = new TreeMap<String, String>();
        for (var slot : inputSlots.properties()) slots.put(slot.getKey(), string(slot.getValue(), MAX_SLOT_BYTES));
        var created = runs.create(owner, key, new PracticeRuns.Create(id, version.intValue(), caseId, mode, slots));
        var response = created.fresh() ? ResponseEntity.created(URI.create("/api/runs/" + created.run().runId()))
                : ResponseEntity.ok();
        return response.cacheControl(CacheControl.noStore()).body(created.run());
    }

    @GetMapping("/api/runs/{id}")
    ResponseEntity<?> run(HttpServletRequest request, @PathVariable String id) throws IOException {
        return ok(runs.get(owner(request), id));
    }

    @PostMapping(path = "/api/runs/{id}/result", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> result(HttpServletRequest request, @PathVariable String id) throws IOException {
        String owner = writeOwner(request);
        JsonNode result = read(request, MAX_RESULT_BYTES);
        String status = string(result.path("status"), 16);
        String raw = null;
        String failure = null;
        switch (status) {
            case "COMPLETED" -> {
                fields(result, Set.of("status", "rawOutput", "reported"));
                raw = string(result.path("rawOutput"), JudgeService.MAX_OUTPUT_BYTES);
                if (raw.isBlank()) throw new PracticeError(422, "INVALID_RESULT");
            }
            case "FAILED" -> {
                fields(result, Set.of("status", "failureCode", "reported"));
                failure = string(result.path("failureCode"), 64);
                if (!FAILURES.contains(failure)) throw new PracticeError(422, "INVALID_RESULT");
            }
            case "CANCELED" -> fields(result, Set.of("status", "reported"));
            default -> throw new PracticeError(422, "INVALID_RESULT");
        }
        Map<String, Object> reported = result.has("reported") ? reported(result.path("reported")) : Map.of();
        return ok(runs.submit(owner, id, new PracticeRuns.Submission(status, raw, failure, reported)));
    }

    private static Map<String, Object> reported(JsonNode node) {
        fields(node, Set.of("modelId", "finishReason", "durationMs", "promptTokens", "completionTokens", "totalTokens"));
        var accepted = new TreeMap<String, Object>();
        for (var field : node.properties()) {
            var value = field.getValue();
            switch (field.getKey()) {
                case "modelId" -> accepted.put("modelId", string(value, 256));
                case "finishReason" -> {
                    String reason = string(value, 32);
                    if (!Set.of("stop", "length", "content_filter", "tool_calls").contains(reason)) {
                        throw new PracticeError(422, "INVALID_RESULT");
                    }
                    accepted.put("finishReason", reason);
                }
                default -> {
                    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
                        throw new PracticeError(422, "INVALID_RESULT");
                    }
                    accepted.put(field.getKey(), value.intValue());
                }
            }
        }
        return accepted;
    }

    private static JsonNode localized(ProblemCatalog.ProblemPack pack, String locale) {
        JsonNode text = pack.translations().get(locale);
        if (text == null) throw new PracticeError(400, "INVALID_LOCALE");
        return text;
    }

    private static void local(HttpServletRequest request) throws IOException {
        String host = request.getHeader("Host");
        if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                || host == null || !host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1])(?::[0-9]{1,5})?")
                || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
            throw new PracticeError(403, "FORBIDDEN");
        }
    }

    private String owner(HttpServletRequest request) throws IOException {
        if (!localProfile) return Sessions.owner(request);
        local(request);
        return runs.localOwner();
    }

    private String writeOwner(HttpServletRequest request) throws IOException {
        String owner = owner(request);
        if (!localProfile) return owner;
        String supplied = request.getHeader(CSRF);
        if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                csrf.getBytes(StandardCharsets.UTF_8))) {
            throw new PracticeError(403, "CSRF_INVALID");
        }
        return owner;
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
