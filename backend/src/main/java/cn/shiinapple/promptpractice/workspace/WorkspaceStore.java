package cn.shiinapple.promptpractice.workspace;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

import cn.shiinapple.promptpractice.practice.PracticeError;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class WorkspaceStore {
    private static final String DRAFT_KEY = "user_id = :owner AND problem_id = :problem AND version = :version";
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate write;

    WorkspaceStore(JdbcClient jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.write = new TransactionTemplate(transactions);
    }

    Settings settings(String owner) {
        return jdbc.sql("SELECT locale, editor_prefs_json, updated_at FROM user_settings WHERE user_id = ?")
                .param(owner).query(this::mapSettings).optional()
                .orElseGet(() -> new Settings("zh-CN", json.createObjectNode(), null));
    }

    Settings patchSettings(String owner, String locale, String prefs) {
        return jdbc.sql("""
                INSERT INTO user_settings(user_id, locale, editor_prefs_json, updated_at)
                VALUES (:owner, COALESCE(:locale, 'zh-CN'), :prefs, :now)
                ON CONFLICT(user_id) DO UPDATE SET
                    locale = COALESCE(:locale, user_settings.locale),
                    editor_prefs_json = json_patch(user_settings.editor_prefs_json, excluded.editor_prefs_json),
                    updated_at = excluded.updated_at
                RETURNING locale, editor_prefs_json, updated_at
                """).param("owner", owner).param("locale", locale).param("prefs", prefs)
                .param("now", System.currentTimeMillis()).query(this::mapSettings).single();
    }

    Draft draft(String owner, String problem, int version) {
        return draftKey("SELECT * FROM drafts WHERE " + DRAFT_KEY, owner, problem, version)
                .query(this::mapDraft).optional().orElse(null);
    }

    Draft saveDraft(String owner, String problem, int version, String caseId, Map<String, String> slots, long revision) {
        String encoded = json.writeValueAsString(slots);
        return write.execute(status -> {
            String sql = revision == 0 ? """
                    INSERT INTO drafts(user_id, problem_id, version, case_id, slots_json, revision, updated_at)
                    VALUES (:owner, :problem, :version, :caseId, :slots, 1, :now)
                    ON CONFLICT(user_id, problem_id, version) DO NOTHING
                    """ : """
                    UPDATE drafts SET case_id = :caseId, slots_json = :slots, revision = revision + 1, updated_at = :now
                    WHERE revision = :revision AND
                    """ + DRAFT_KEY;
            var update = draftKey(sql, owner, problem, version).param("caseId", caseId)
                    .param("slots", encoded).param("now", System.currentTimeMillis());
            if (revision != 0) update.param("revision", revision);
            int rows = update.update();
            Draft saved = draft(owner, problem, version);
            if (rows == 0) throw new PracticeError(409, "DRAFT_REVISION_CONFLICT",
                    Map.of("serverRevision", saved == null ? 0L : saved.revision()));
            return saved;
        });
    }

    private JdbcClient.StatementSpec draftKey(String sql, String owner, String problem, int version) {
        return jdbc.sql(sql).param("owner", owner).param("problem", problem).param("version", version);
    }

    private Settings mapSettings(ResultSet row, int index) throws SQLException {
        return new Settings(row.getString("locale"), json.readTree(row.getString("editor_prefs_json")),
                Instant.ofEpochMilli(row.getLong("updated_at")).toString());
    }

    private Draft mapDraft(ResultSet row, int index) throws SQLException {
        return new Draft(row.getString("problem_id"), row.getInt("version"), row.getString("case_id"),
                json.readTree(row.getString("slots_json")), row.getLong("revision"),
                Instant.ofEpochMilli(row.getLong("updated_at")).toString());
    }

    record Settings(String locale, JsonNode editorPrefs, String updatedAt) {}
    record Draft(String problemId, int version, String caseId, JsonNode slots, long revision, String updatedAt) {
        @Override
        public String toString() {
            return "Draft[problemId=" + problemId + ", version=" + version + ", revision=" + revision + "]";
        }
    }
}
