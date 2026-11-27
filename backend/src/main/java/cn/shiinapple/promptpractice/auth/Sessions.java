package cn.shiinapple.promptpractice.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import cn.shiinapple.promptpractice.practice.PracticeError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

@Component
public class Sessions {
    public static final String HEADER = "X-Practice-CSRF";
    private static final String CSRF = Sessions.class.getName() + ".csrf";
    private static final String SUBJECT = Sessions.class.getName() + ".subject";
    private static final String PROFILE = Sessions.class.getName() + ".profile";
    private final Accounts accounts;

    Sessions(Accounts accounts) {
        this.accounts = accounts;
    }

    String token(HttpSession session) {
        synchronized (session) {
            String token = (String) session.getAttribute(CSRF);
            if (token == null) {
                token = UUID.randomUUID().toString();
                session.setAttribute(CSRF, token);
            }
            return token;
        }
    }

    void verifyCsrf(HttpServletRequest request) {
        String supplied = request.getHeader(HEADER);
        String expected = (String) attribute(request.getSession(false), CSRF);
        if (supplied == null || expected == null || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new PracticeError(403, "CSRF_INVALID");
        }
    }

    void login(HttpServletRequest request, String id) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new PracticeError(403, "CSRF_INVALID");
        synchronized (session) {
            verifyCsrf(request);
            request.changeSessionId();
            session.setAttribute(SUBJECT, id);
            session.setAttribute(CSRF, UUID.randomUUID().toString());
        }
    }

    void requireActive(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object subject = attribute(session, SUBJECT);
        if (subject instanceof String id) {
            var account = accounts.active(id);
            if (account.isPresent()) {
                request.setAttribute(PROFILE, account.get());
                return;
            }
            invalidate(session);
        }
        throw new PracticeError(401, "AUTH_REQUIRED");
    }

    public static String owner(HttpServletRequest request) {
        return profile(request).userId();
    }

    static Accounts.Profile profile(HttpServletRequest request) {
        return (Accounts.Profile) request.getAttribute(PROFILE);
    }

    static void invalidate(HttpSession session) {
        if (session == null) return;
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            // An overlapping logout or revocation can win first.
        }
    }

    private static Object attribute(HttpSession session, String name) {
        if (session == null) return null;
        try {
            return session.getAttribute(name);
        } catch (IllegalStateException alreadyInvalidated) {
            return null;
        }
    }
}
