package cn.shiinapple.promptpractice.practice;

import java.net.URI;
import java.util.UUID;

import cn.shiinapple.promptpractice.common.SqliteWrites;
import cn.shiinapple.promptpractice.problem.ProblemCatalog;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ProblemCatalog.UnknownProblem.class)
    ResponseEntity<ProblemDetail> unknownProblem() {
        return error(new PracticeError(404, "RESOURCE_NOT_FOUND"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> databaseError(RuntimeException cause) {
        if (SqliteWrites.isBusy(cause)) return error(new PracticeError(503, "TEMPORARILY_UNAVAILABLE"));
        var response = error(new PracticeError(500, "INTERNAL_ERROR"));
        LoggerFactory.getLogger(ApiErrors.class).error("Database request failed, requestId={}, type={}",
                response.getBody().getProperties().get("requestId"), cause.getClass().getSimpleName());
        return response;
    }

    @ExceptionHandler(PracticeError.class)
    ResponseEntity<ProblemDetail> error(PracticeError error) {
        var detail = ProblemDetail.forStatus(error.status);
        detail.setType(URI.create("about:blank"));
        detail.setProperty("code", error.code);
        detail.setProperty("messageKey", error.code.equals("DRAFT_REVISION_CONFLICT")
                ? "errors.draftRevisionConflict" : "errors." + error.code);
        if (!error.details.isEmpty()) detail.setProperty("details", error.details);
        detail.setProperty("requestId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (error.status == 503 || error.status == 429) response.header("Retry-After", error.status == 503 ? "1" : "60");
        return response.body(detail);
    }

}
