package cn.shiinapple.promptpractice.common;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InfrastructureController implements ErrorController {
    private static final Logger log = LoggerFactory.getLogger(InfrastructureController.class);
    private static final URI DEFAULT_PROBLEM_TYPE = URI.create("about:blank");

    private final ApplicationAvailability availability;

    InfrastructureController(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @GetMapping("/api/health")
    ResponseEntity<?> health() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", "UP"));
    }

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus httpStatus = status instanceof Integer code ? HttpStatus.resolve(code) : HttpStatus.NOT_FOUND;
        return problem(httpStatus == null ? HttpStatus.INTERNAL_SERVER_ERROR : httpStatus);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status) {
        String requestId = UUID.randomUUID().toString();
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("Unhandled HTTP error, requestId={}", requestId);
        }

        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(DEFAULT_PROBLEM_TYPE);
        String code = switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case SERVICE_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE";
            case INTERNAL_SERVER_ERROR -> "INTERNAL_ERROR";
            default -> "HTTP_" + status.value();
        };
        String messageKey = switch (status) {
            case BAD_REQUEST -> "errors.invalidRequest";
            case NOT_FOUND -> "errors.resourceNotFound";
            case METHOD_NOT_ALLOWED -> "errors.methodNotAllowed";
            case UNSUPPORTED_MEDIA_TYPE -> "errors.unsupportedMediaType";
            case SERVICE_UNAVAILABLE -> "errors.temporarilyUnavailable";
            case INTERNAL_SERVER_ERROR -> "errors.internalError";
            default -> "errors.requestFailed";
        };
        problem.setProperty("code", code);
        problem.setProperty("messageKey", messageKey);
        problem.setProperty("requestId", requestId);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
