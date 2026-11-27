package cn.shiinapple.promptpractice.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import cn.shiinapple.promptpractice.common.SqliteWrites;
import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
class HistoryStore {
    private static final String SUMMARY = """
            SELECT r.id AS run_id, s.problem_id, s.version, s.case_id, r.mode, r.verdict, r.created_at, r.expires_at,
                   CASE WHEN r.status = 'PENDING' AND r.expires_at <= :now THEN 'EXPIRED' ELSE r.status END AS status,
                   CASE WHEN r.status = 'PENDING' AND r.expires_at <= :now THEN r.expires_at ELSE r.finished_at END AS finished_at,
                   a.id AS submission_id, a.submitted_at
            FROM runs r JOIN prompt_snapshots s ON s.user_id = r.user_id AND s.id = r.snapshot_id
            """;
    private final JdbcClient jdbc;
    private final SqliteWrites writes;
    private final Cursors cursors;

    HistoryStore(JdbcClient jdbc, PlatformTransactionManager transactions, Cursors cursors) {
        this.jdbc = jdbc;
        this.writes = new SqliteWrites(transactions, jdbc);
        this.cursors = cursors;
    }

    Page runs(String owner, String problem, int limit, String cursor) {
        return page(owner, "runs:" + problem, limit, cursor, """
                LEFT JOIN submissions a ON a.user_id = r.user_id AND a.run_id = r.id
                WHERE r.user_id = :owner AND s.problem_id = :problem
                """, problem, "r.created_at", "r.id");
    }

    Page submissions(String owner, int limit, String cursor) {
        return page(owner, "submissions", limit, cursor, """
                JOIN submissions a ON a.user_id = r.user_id AND a.run_id = r.id
                WHERE a.user_id = :owner
                """, null, "a.submitted_at", "a.id");
    }

    private Page page(String owner, String scope, int limit, String cursor, String filter, String problem,
            String timeColumn, String idColumn) {
        var position = cursors.read(owner, scope, cursor);
        String paging = position == null ? "" : " AND (" + timeColumn + ", " + idColumn + ") < (:time, :id)";
        var query = jdbc.sql(SUMMARY + filter + paging + " ORDER BY " + timeColumn + " DESC, " + idColumn
                + " DESC LIMIT :limit").param("owner", owner).param("now", System.currentTimeMillis())
                .param("limit", limit + 1);
        if (problem != null) query.param("problem", problem);
        if (position != null) query.param("time", position.time()).param("id", position.id());
        var rows = query.query((rs, index) -> mapItem(rs, problem == null)).list();
        if (rows.size() <= limit) return new Page(rows.stream().map(Row::item).toList(), null);
        return new Page(rows.subList(0, limit).stream().map(Row::item).toList(),
                cursors.encode(owner, scope, rows.get(limit - 1).position()));
    }

    Archived archive(String owner, String runId) {
        return writes.execute(() -> {
            int inserted = jdbc.sql("""
                    INSERT INTO submissions(id, user_id, run_id, submitted_at)
                    SELECT :id, user_id, id, :now FROM runs
                    WHERE user_id = :owner AND id = :run AND status = 'COMPLETED'
                    ON CONFLICT(run_id) DO NOTHING
                    """).param("id", UUID.randomUUID().toString()).param("now", System.currentTimeMillis())
                    .param("owner", owner).param("run", runId).update();
            var saved = jdbc.sql("""
                    SELECT a.id, a.submitted_at, r.status
                    FROM runs r LEFT JOIN submissions a ON a.user_id = r.user_id AND a.run_id = r.id
                    WHERE r.user_id = ? AND r.id = ?
                    """).params(owner, runId).query((rs, row) -> new ArchiveRow(rs.getString(1), rs.getLong(2),
                            rs.getString(3))).optional().orElseThrow(HistoryStore::notFound);
            if (!"COMPLETED".equals(saved.status())) throw new PracticeError(409, "RUN_NOT_COMPLETED");
            return new Archived(inserted == 1, new Submission(saved.id(), runId, instant(saved.submittedAt())));
        });
    }

    void unarchive(String owner, String submissionId) {
        int deleted = writes.execute(() -> jdbc.sql("DELETE FROM submissions WHERE user_id = ? AND id = ?")
                .params(owner, submissionId).update());
        if (deleted == 0) throw notFound();
    }

    private static Row mapItem(ResultSet rs, boolean archivedOrder) throws SQLException {
        String runId = rs.getString("run_id");
        String submissionId = rs.getString("submission_id");
        long created = rs.getLong("created_at");
        long submitted = rs.getLong("submitted_at");
        long finished = rs.getLong("finished_at");
        String finishedAt = rs.wasNull() ? null : instant(finished);
        var item = new Item(runId, rs.getString("problem_id"), rs.getInt("version"), rs.getString("case_id"),
                rs.getString("mode"), rs.getString("status"), rs.getString("verdict"), instant(created),
                instant(rs.getLong("expires_at")), finishedAt, submissionId,
                submissionId == null ? null : instant(submitted));
        var position = archivedOrder ? new Cursors.Position(submitted, submissionId) : new Cursors.Position(created, runId);
        return new Row(position, item);
    }

    private static String instant(long time) {
        return Instant.ofEpochMilli(time).toString();
    }

    private static PracticeError notFound() {
        return new PracticeError(404, "RESOURCE_NOT_FOUND");
    }

    private record Row(Cursors.Position position, Item item) {}
    private record ArchiveRow(String id, long submittedAt, String status) {}
    record Page(List<Item> items, String nextCursor) {}
    record Item(String runId, String problemId, int version, String caseId, String mode, String status, String verdict,
            String createdAt, String expiresAt, String finishedAt, String submissionId, String submittedAt) {}
    record Submission(String submissionId, String runId, String submittedAt) {}
    record Archived(boolean created, Submission submission) {}
}
