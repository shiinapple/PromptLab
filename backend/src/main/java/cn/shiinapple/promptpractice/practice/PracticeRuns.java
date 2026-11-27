package cn.shiinapple.promptpractice.practice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteException;

import cn.shiinapple.promptpractice.judge.JudgeService;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local-practice")
public class PracticeRuns implements ApplicationRunner {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long TTL = Duration.ofHours(24).toMillis();
    private static final String SELECT = """
            SELECT r.*, s.problem_id, s.version, s.case_id, s.messages_json, s.request_params_json, v.content_hash
            FROM runs r JOIN prompt_snapshots s ON s.id = r.snapshot_id
            JOIN problem_versions v ON v.problem_id = s.problem_id AND v.version = s.version
            WHERE r.user_id = :owner AND
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate write;
    private final ProblemCatalog catalog;
    private final JudgeService judge;
    private final String owner;
    private final Clock clock;

    @Autowired
    PracticeRuns(JdbcClient jdbc, PlatformTransactionManager transactions, ProblemCatalog catalog, JudgeService judge,
            @Value("${prompt-practice.local-subject:local-practice}") String owner) {
        this(jdbc, transactions, catalog, judge, owner, Clock.systemUTC());
    }

    PracticeRuns(JdbcClient jdbc, PlatformTransactionManager transactions, ProblemCatalog catalog, JudgeService judge,
            String owner, Clock clock) {
        if (!owner.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid local subject");
        this.jdbc = jdbc;
        this.write = new TransactionTemplate(transactions);
        this.catalog = catalog;
        this.judge = judge;
        this.owner = owner;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        var packs = catalog.loadPublished();
        inWrite(() -> {
            long now = clock.millis();
            jdbc.sql("INSERT INTO users(id, created_at) VALUES (:owner, :now) ON CONFLICT DO NOTHING")
                    .param("owner", owner).param("now", now).update();
            for (var pack : packs) {
                jdbc.sql("""
                        INSERT INTO problem_versions(problem_id, version, content_hash, published_at)
                        VALUES (:id, :version, :hash, :now) ON CONFLICT DO NOTHING
                        """).param("id", pack.problemId()).param("version", pack.version())
                        .param("hash", pack.contentHash()).param("now", now).update();
                String hash = jdbc.sql("SELECT content_hash FROM problem_versions WHERE problem_id = :id AND version = :version")
                        .param("id", pack.problemId()).param("version", pack.version()).query(String.class).single();
                if (!hash.equals(pack.contentHash())) throw new IllegalStateException("Published problem bytes changed");
                for (var problemCase : pack.cases()) {
                    jdbc.sql("INSERT INTO problem_cases VALUES (:id, :version, :caseId) ON CONFLICT DO NOTHING")
                            .param("id", pack.problemId()).param("version", pack.version())
                            .param("caseId", problemCase.caseId()).update();
                }
            }
            return null;
        });
    }

    Created create(String key, Create intent) {
        String digest = digest(List.of("create-v1", intent.problemId(), intent.version(), intent.caseId(),
                intent.mode(), new TreeMap<>(intent.slots()), Map.of()));
        Stored existing = byKey(key);
        if (existing != null) return reused(existing, digest);

        var pack = catalog.loadPublished(intent.problemId());
        if (intent.version() != pack.version()) throw new PracticeError(409, "VERSION_CHANGED");
        var problemCase = pack.findCase(intent.caseId());
        if (problemCase == null) throw new PracticeError(422, "INVALID_CASE");

        List<MessageAssembler.Message> messages;
        try {
            messages = MessageAssembler.assemble(pack.problem(), intent.slots(), problemCase.variables());
        } catch (IllegalArgumentException error) {
            throw new PracticeError(422, "INVALID_SLOT");
        }
        String slots = JSON.writeValueAsString(new TreeMap<>(intent.slots()));
        String assembled = JSON.writeValueAsString(messages);
        return inWrite(() -> {
            Stored winner = byKey(key);
            if (winner != null) return reused(winner, digest);
            long now = clock.millis();
            String snapshotId = UUID.randomUUID().toString();
            String runId = UUID.randomUUID().toString();
            jdbc.sql("""
                    INSERT INTO prompt_snapshots
                      (id, user_id, problem_id, version, case_id, slots_json, messages_json, request_params_json, created_at)
                    VALUES (:snapshot, :owner, :problem, :version, :caseId, :slots, :messages, '{}', :now)
                    """).param("snapshot", snapshotId).param("owner", owner)
                    .param("problem", intent.problemId()).param("version", intent.version())
                    .param("caseId", intent.caseId()).param("slots", slots).param("messages", assembled)
                    .param("now", now).update();
            jdbc.sql("""
                    INSERT INTO runs (id, user_id, snapshot_id, mode, idempotency_key, create_digest, status, created_at, expires_at)
                    VALUES (:id, :owner, :snapshot, :mode, :key, :digest, 'PENDING', :now, :expires)
                    """).param("id", runId).param("owner", owner).param("snapshot", snapshotId)
                    .param("mode", intent.mode()).param("key", key).param("digest", digest)
                    .param("now", now).param("expires", now + TTL).update();
            return new Created(true, byId(runId).view(now));
        });
    }

    Run get(String id) {
        return byId(id).view(clock.millis());
    }

    Run submit(String id, Submission intent) {
        String digest = digest(List.of("result-v1", intent.status(), intent.rawOutput() == null ? "" : intent.rawOutput(),
                intent.failureCode() == null ? "" : intent.failureCode(), new TreeMap<>(intent.reported())));
        Stored before = byId(id);
        Run replay = existingResult(before, digest, clock.millis());
        if (replay != null) return replay;

        JudgeService.Result grade = null;
        if ("COMPLETED".equals(intent.status())) {
            var pack = catalog.loadVersion(before.problemId(), before.version());
            var problemCase = pack.findCase(before.caseId());
            if (!pack.contentHash().equals(before.contentHash()) || problemCase == null) {
                throw new IllegalStateException("Run problem snapshot unavailable");
            }
            try {
                grade = judge.judge(intent.rawOutput(), pack.schema(), problemCase.assertions());
            } catch (JudgeService.OutputLimit limit) {
                throw new PracticeError(413, "PAYLOAD_TOO_LARGE");
            }
        }
        String verdict = grade == null ? null : grade.verdict();
        String checks = grade == null ? null : JSON.writeValueAsString(grade.checks());
        String reported = intent.reported().isEmpty() ? null : JSON.writeValueAsString(intent.reported());
        return inWrite(() -> {
            // TransactionTemplate has acquired the IMMEDIATE write reservation before this clock read.
            long now = clock.millis();
            int updated = jdbc.sql("""
                    UPDATE runs SET status = :status, result_digest = :digest, raw_output = :raw,
                      reported_meta_json = :meta, failure_code = :failureCode, verdict = :verdict,
                      checks_json = :checks, finished_at = :now
                    WHERE id = :id AND user_id = :owner AND status = 'PENDING' AND expires_at > :now
                    """).param("status", intent.status()).param("digest", digest).param("raw", intent.rawOutput())
                    .param("meta", reported).param("failureCode", intent.failureCode()).param("verdict", verdict)
                    .param("checks", checks).param("now", now).param("id", id).param("owner", owner).update();
            Stored winner = byId(id);
            return updated == 1 ? winner.view(now) : existingResult(winner, digest, now);
        });
    }

    private Run existingResult(Stored run, String digest, long now) {
        if ("PENDING".equals(run.status()) && run.expiresAt() > now) return null;
        if ("EXPIRED".equals(run.status()) || "PENDING".equals(run.status())) {
            throw new PracticeError(410, "RUN_EXPIRED");
        }
        if (!digest.equals(run.resultDigest())) throw new PracticeError(409, "RESULT_CONFLICT");
        return run.view(now);
    }

    private Created reused(Stored run, String digest) {
        if (!digest.equals(run.createDigest())) throw new PracticeError(409, "IDEMPOTENCY_CONFLICT");
        return new Created(false, run.view(clock.millis()));
    }

    private Stored byKey(String key) {
        return jdbc.sql(SELECT + " r.idempotency_key = :key").param("owner", owner).param("key", key)
                .query((rs, row) -> stored(rs)).optional().orElse(null);
    }

    private Stored byId(String id) {
        return jdbc.sql(SELECT + " r.id = :id").param("owner", owner).param("id", id)
                .query((rs, row) -> stored(rs)).optional().orElseThrow(() -> new PracticeError(404, "RESOURCE_NOT_FOUND"));
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        return new Stored(rs.getString("id"), rs.getString("problem_id"), rs.getInt("version"),
                rs.getString("case_id"), rs.getString("content_hash"), rs.getString("mode"), rs.getString("status"),
                rs.getString("create_digest"), rs.getString("result_digest"), rs.getLong("created_at"),
                rs.getLong("expires_at"), rs.getObject("finished_at") == null ? null : rs.getLong("finished_at"),
                json(rs, "messages_json"), json(rs, "request_params_json"), rs.getString("raw_output"),
                rs.getString("verdict"), json(rs, "checks_json"), json(rs, "reported_meta_json"), rs.getString("failure_code"));
    }

    private static JsonNode json(ResultSet rs, String column) throws SQLException {
        String data = rs.getString(column);
        return data == null ? null : JSON.readTree(data);
    }

    private <T> T inWrite(Supplier<T> operation) {
        try {
            return write.execute(status -> operation.get());
        } catch (RuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == 5) {
                    throw new PracticeError(503, "TEMPORARILY_UNAVAILABLE");
                }
            }
            throw error;
        }
    }

    private static String digest(Object ordered) {
        try {
            byte[] json = JSON.writeValueAsString(ordered).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    record Create(String problemId, int version, String caseId, String mode, Map<String, String> slots) {
    }

    record Submission(String status, String rawOutput, String failureCode, Map<String, Object> reported) {
    }

    record Created(boolean fresh, Run run) {
    }

    record Run(String runId, String problemId, int version, String caseId, String mode, String status,
            String createdAt, String expiresAt, String finishedAt, JsonNode messages, JsonNode requestParams,
            String rawOutput, String verdict, JsonNode checks, JsonNode reported, String failureCode) {
        @Override
        public String toString() {
            // MVC debug logging must not render the Prompt or model output.
            return "Run[runId=" + runId + ", status=" + status + "]";
        }
    }

    private record Stored(String id, String problemId, int version, String caseId, String contentHash, String mode,
            String status, String createDigest, String resultDigest, long createdAt, long expiresAt, Long finishedAt,
            JsonNode messages, JsonNode params, String raw, String verdict, JsonNode checks, JsonNode reported, String failure) {
        Run view(long now) {
            boolean expired = "PENDING".equals(status) && expiresAt <= now;
            String finished = expired ? instant(expiresAt) : finishedAt == null ? null : instant(finishedAt);
            return new Run(id, problemId, version, caseId, mode, expired ? "EXPIRED" : status, instant(createdAt),
                    instant(expiresAt), finished, messages, params, raw, verdict, checks, reported, failure);
        }

        private static String instant(long timestamp) {
            return Instant.ofEpochMilli(timestamp).toString();
        }
    }
}
