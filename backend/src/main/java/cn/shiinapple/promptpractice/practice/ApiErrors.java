package cn.shiinapple.promptpractice.practice;

import java.net.URI;
import java.util.UUID;

import cn.shiinapple.promptpractice.problem.ProblemCatalog;
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

}
