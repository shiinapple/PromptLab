package cn.shiinapple.promptpractice.history;

import java.io.IOException;
import java.util.Set;

import cn.shiinapple.promptpractice.auth.Sessions;
import cn.shiinapple.promptpractice.practice.ApiInput;
import cn.shiinapple.promptpractice.practice.PracticeError;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HistoryController {
    private final HistoryStore store;
    private final ProblemCatalog catalog;

    HistoryController(HistoryStore store, ProblemCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping("/api/problems/{problemId}/runs")
    HistoryStore.Page runs(HttpServletRequest request, @PathVariable String problemId,
            @RequestParam(defaultValue = "20") String limit, @RequestParam(required = false) String cursor) {
        catalog.loadPublished(problemId);
        return store.runs(Sessions.owner(request), problemId, pageLimit(limit), cursor);
    }

    @GetMapping("/api/submissions")
    HistoryStore.Page submissions(HttpServletRequest request, @RequestParam(defaultValue = "20") String limit,
            @RequestParam(required = false) String cursor) {
        return store.submissions(Sessions.owner(request), pageLimit(limit), cursor);
    }

    @PostMapping(path = "/api/submissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HistoryStore.Submission> archive(HttpServletRequest request) throws IOException {
        var body = ApiInput.read(request, 1024);
        ApiInput.fields(body, Set.of("runId"));
        var archived = store.archive(Sessions.owner(request), ApiInput.string(body.path("runId"), 64));
        return ResponseEntity.status(archived.created() ? 201 : 200).body(archived.submission());
    }

    @DeleteMapping("/api/submissions/{submissionId}")
    ResponseEntity<Void> unarchive(HttpServletRequest request, @PathVariable String submissionId) {
        store.unarchive(Sessions.owner(request), submissionId);
        return ResponseEntity.noContent().build();
    }

    private static int pageLimit(String value) {
        if (!value.matches("([1-9][0-9]?|100)")) throw new PracticeError(400, "INVALID_REQUEST");
        return Integer.parseInt(value);
    }
}
