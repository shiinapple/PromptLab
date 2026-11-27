package cn.shiinapple.promptpractice.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import cn.shiinapple.promptpractice.practice.PracticeError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.springframework.stereotype.Component;

@Component
public class Sessions {
    public static final String HEADER = "X-Practice-CSRF";
    private static final String CSRF = Sessions.class.getName() + ".csrf";
    private static final String SUBJECT = Sessions.class.getName() + ".subject";
    private static final String PROFILE = Sessions.class.getName() + ".profile";
    private final Accounts accounts;
    private final ConcurrentHashMap<String, Set<HttpSession>> authenticated = new ConcurrentHashMap<>();

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
            if (accounts.active(id).isEmpty()) throw new PracticeError(401, "INVALID_CREDENTIALS");
            request.changeSessionId();
            session.removeAttribute(SUBJECT);
            session.setAttribute(SUBJECT, new Binding(id));
            session.setAttribute(CSRF, UUID.randomUUID().toString());
        }
    }

    void requireActive(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object subject = attribute(session, SUBJECT);
        if (subject instanceof Binding binding) {
            var account = accounts.active(binding.id);
            if (account.isPresent()) {
                request.setAttribute(PROFILE, account.get());
                return;
            }
            revoke(binding.id);
            invalidate(session);
        }
        throw new PracticeError(401, "AUTH_REQUIRED");
    }

    public void revokeInactive() {
        var subjects = List.copyOf(authenticated.keySet());
        if (subjects.isEmpty()) return;
        var active = accounts.activeIds();
        for (String id : subjects) {
            if (!active.contains(id)) revoke(id);
        }
    }

    private void revoke(String id) {
        var sessions = authenticated.remove(id);
        if (sessions != null) sessions.forEach(Sessions::invalidate);
    }

    private final class Binding implements HttpSessionBindingListener {
        private final String id;

        Binding(String id) {
            this.id = id;
        }

        @Override
        public void valueBound(HttpSessionBindingEvent event) {
            authenticated.compute(id, (key, sessions) -> {
                if (sessions == null) sessions = ConcurrentHashMap.newKeySet();
                sessions.add(event.getSession());
                return sessions;
            });
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event) {
            authenticated.computeIfPresent(id, (key, sessions) -> {
                sessions.remove(event.getSession());
                return sessions.isEmpty() ? null : sessions;
            });
        }
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
