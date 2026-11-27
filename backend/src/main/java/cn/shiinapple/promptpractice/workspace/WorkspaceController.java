package cn.shiinapple.promptpractice.workspace;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import cn.shiinapple.promptpractice.auth.Sessions;
import cn.shiinapple.promptpractice.practice.ApiInput;
import cn.shiinapple.promptpractice.practice.PracticeError;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WorkspaceController {
    private static final int MAX_SLOT_BYTES = 8 * 1024;
    private final WorkspaceStore store;
    private final ProblemCatalog catalog;

    WorkspaceController(WorkspaceStore store, ProblemCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping("/api/settings")
    WorkspaceStore.Settings settings(HttpServletRequest request) {
        return store.settings(Sessions.owner(request));
    }

    @PatchMapping(path = "/api/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    WorkspaceStore.Settings patchSettings(HttpServletRequest request) throws IOException {
        var body = ApiInput.read(request, 2048);
        ApiInput.fields(body, Set.of("locale", "editorPrefs"));
        String locale = null;
        if (body.has("locale")) {
            var value = body.path("locale");
            if (!value.isString()) throw new PracticeError(400, "INVALID_LOCALE");
            locale = ApiInput.requireLocale(ApiInput.string(value, 16));
        }
        String prefs = "{}";
        if (body.has("editorPrefs")) {
            var editor = body.path("editorPrefs");
            ApiInput.fields(editor, Set.of("fontSize", "tabSize", "wordWrap"));
            if (editor.has("fontSize")) ApiInput.integer(editor.path("fontSize"), 12, 32);
            if (editor.has("tabSize")) ApiInput.integer(editor.path("tabSize"), 2, 8);
            if (editor.has("wordWrap") && !editor.path("wordWrap").isBoolean()) {
                throw new PracticeError(400, "INVALID_REQUEST");
            }
            prefs = editor.toString();
        }
        return store.patchSettings(Sessions.owner(request), locale, prefs);
    }

    @GetMapping("/api/problems/{problemId}/draft")
    DraftResponse draft(HttpServletRequest request, @PathVariable String problemId,
            @RequestParam(required = false) String version) {
        var pack = catalog.readPublished(problemId, ApiInput.problemVersion(version)).pack();
        return new DraftResponse(store.draft(Sessions.owner(request), problemId, pack.version()));
    }

    @PutMapping(path = "/api/problems/{problemId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DraftResponse> putDraft(HttpServletRequest request, @PathVariable String problemId) throws IOException {
        var body = ApiInput.read(request, MAX_SLOT_BYTES * 6 + 4096);
        ApiInput.fields(body, Set.of("version", "caseId", "slots", "revision"));
        int version = ApiInput.integer(body.path("version"), 1, Integer.MAX_VALUE);
        var revisionNode = body.path("revision");
        if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong() || revisionNode.longValue() < 0) {
            throw new PracticeError(400, "INVALID_REQUEST");
        }
        long revision = revisionNode.longValue();
        var pack = catalog.readPublished(problemId, version).pack();
        String caseId = ApiInput.string(body.path("caseId"), 64);
        if (pack.findCase(caseId) == null) throw new PracticeError(422, "INVALID_CASE");
        var input = body.path("slots");
        if (!input.isObject()) throw new PracticeError(400, "INVALID_REQUEST");
        if (!new HashSet<>(input.propertyNames()).equals(new HashSet<>(pack.problem().path("slots").propertyNames()))) {
            throw new PracticeError(422, "INVALID_SLOT");
        }
        var slots = new TreeMap<String, String>();
        for (var slot : input.properties()) slots.put(slot.getKey(), ApiInput.string(slot.getValue(), MAX_SLOT_BYTES));
        var draft = store.saveDraft(Sessions.owner(request), problemId, version, caseId, slots, revision);
        return ResponseEntity.status(revision == 0 ? 201 : 200).body(new DraftResponse(draft));
    }

    record DraftResponse(WorkspaceStore.Draft draft) {}
}
