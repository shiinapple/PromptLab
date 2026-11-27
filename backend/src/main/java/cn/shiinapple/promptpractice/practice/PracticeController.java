package cn.shiinapple.promptpractice.practice;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import cn.shiinapple.promptpractice.judge.JudgeService;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;

@RestController
@Profile("local-practice")
class PracticeController {
    private static final String CSRF = "X-Practice-CSRF";
    private static final int MAX_SLOT_BYTES = 8 * 1024;
    private static final int MAX_CREATE_BYTES = MAX_SLOT_BYTES * 6 + 4096;
    private static final int MAX_RESULT_BYTES = JudgeService.MAX_OUTPUT_BYTES * 6 + 4096;
    private static final Set<String> FAILURES = Set.of("TIMEOUT", "UPSTREAM_ERROR", "TRUNCATED", "PROTOCOL_ERROR");
    private static final JsonMapper INPUT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final PracticeRuns runs;
    private final ProblemCatalog catalog;
    private final String csrf = UUID.randomUUID().toString();

    PracticeController(PracticeRuns runs, ProblemCatalog catalog) {
        this.runs = runs;
        this.catalog = catalog;
    }

    @GetMapping("/api/practice/session")
    ResponseEntity<?> session(HttpServletRequest request) throws IOException {
        local(request);
        return ok(Map.of("token", csrf, "headerName", CSRF));
    }

    @GetMapping("/api/problems")
    ResponseEntity<?> problems(HttpServletRequest request, @RequestParam(defaultValue = "zh-CN") String locale) throws IOException {
        local(request);
        var items = catalog.loadPublished().stream().map(pack -> Map.of(
                "problemId", pack.problemId(), "version", pack.version(), "title", localized(pack, locale).path("title"),
                "category", pack.problem().path("category"), "difficulty", pack.problem().path("difficulty"))).toList();
        return ok(Map.of("items", items));
    }

    @GetMapping("/api/problems/{id}")
    ResponseEntity<?> problem(HttpServletRequest request, @PathVariable String id,
            @RequestParam(defaultValue = "zh-CN") String locale) throws IOException {
        local(request);
        var pack = catalog.loadPublished(id);
        var text = localized(pack, locale);
        return ok(Map.of(
                "problemId", pack.problemId(), "version", pack.version(), "locale", locale,
                "title", text.path("title"), "description", text.path("description"),
                "defaultSlots", text.path("defaultSlots"), "slots", pack.problem().path("slots"),
                "messages", pack.problem().path("messages"),
                "cases", List.of(Map.of("caseId", pack.caseId(), "inputLocale", pack.publicCase().path("inputLocale"),
                        "variables", pack.publicCase().path("variables"),
                        "grading", pack.publicCase().path("assertions").isEmpty() ? "STRUCTURE_ONLY" : "RULES"))));
    }

    @PostMapping(path = "/api/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(HttpServletRequest request) throws IOException {
        mutation(request);
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
        var created = runs.create(key, new PracticeRuns.Create(id, version.intValue(), caseId, mode, slots));
        var response = created.fresh() ? ResponseEntity.created(URI.create("/api/runs/" + created.run().runId()))
                : ResponseEntity.ok();
        return response.cacheControl(CacheControl.noStore()).body(created.run());
    }

    @GetMapping("/api/runs/{id}")
    ResponseEntity<?> run(HttpServletRequest request, @PathVariable String id) throws IOException {
        local(request);
        return ok(runs.get(id));
    }

    @PostMapping(path = "/api/runs/{id}/result", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> result(HttpServletRequest request, @PathVariable String id) throws IOException {
        mutation(request);
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
        return ok(runs.submit(id, new PracticeRuns.Submission(status, raw, failure, reported)));
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

    private static JsonNode read(HttpServletRequest request, int limit) throws IOException {
        if (request.getContentLengthLong() > limit) throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
        byte[] data = request.getInputStream().readNBytes(limit + 1);
        if (data.length > limit) throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
            return INPUT.readTree(utf8);
        } catch (JacksonException | java.nio.charset.CharacterCodingException error) {
            throw new PracticeError(400, "INVALID_JSON");
        }
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new PracticeError(400, "INVALID_REQUEST");
        if (!allowed.containsAll(node.propertyNames())) throw new PracticeError(400, "UNKNOWN_FIELD");
    }

    private static String string(JsonNode node, int maxBytes) {
        if (!node.isString()) throw new PracticeError(400, "INVALID_REQUEST");
        String text = node.stringValue();
        if (text.length() > maxBytes || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw new PracticeError(400, "INVALID_JSON");
        return text;
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

    private void mutation(HttpServletRequest request) throws IOException {
        local(request);
        String supplied = request.getHeader(CSRF);
        if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                csrf.getBytes(StandardCharsets.UTF_8))) {
            throw new PracticeError(403, "CSRF_INVALID");
        }
    }

    @ExceptionHandler(ProblemCatalog.UnknownProblem.class)
    ResponseEntity<ProblemDetail> unknownProblem() {
        return error(new PracticeError(404, "RESOURCE_NOT_FOUND"));
    }

    @ExceptionHandler(PracticeError.class)
    ResponseEntity<ProblemDetail> error(PracticeError error) {
        var detail = ProblemDetail.forStatus(error.status);
        detail.setType(URI.create("about:blank"));
        detail.setProperty("code", error.code);
        detail.setProperty("messageKey", "errors." + error.code);
        detail.setProperty("requestId", UUID.randomUUID().toString());
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
