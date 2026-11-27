package cn.shiinapple.promptpractice.auth;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import cn.shiinapple.promptpractice.practice.ApiInput;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {
    private final Accounts accounts;
    private final Sessions sessions;
    private final LoginLimits limits;

    AuthController(Accounts accounts, Sessions sessions, LoginLimits limits) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.limits = limits;
    }

    @GetMapping("/api/auth/csrf")
    Map<String, String> csrf(HttpServletRequest request) {
        return Map.of("token", sessions.token(request.getSession()), "headerName", Sessions.HEADER);
    }

    @PostMapping(path = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> login(HttpServletRequest request) throws IOException {
        var body = ApiInput.read(request, 8192);
        ApiInput.fields(body, Set.of("email", "password"));
        String email = Accounts.normalizeEmail(ApiInput.string(body.path("email"), 254));
        String password = ApiInput.string(body.path("password"), 1024);
        String id = limits.authenticate(accounts, request.getRemoteAddr(), email, password);
        sessions.login(request, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        Sessions.invalidate(request.getSession(false));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/me")
    Accounts.Profile me(HttpServletRequest request) {
        return Sessions.profile(request);
    }
}
