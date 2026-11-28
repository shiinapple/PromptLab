package cn.shiinapple.promptpractice.auth;

import cn.shiinapple.promptpractice.practice.PracticeController;
import cn.shiinapple.promptpractice.practice.PracticeError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class ApiAccess implements WebMvcConfigurer {
    private final Environment environment;
    private final Sessions sessions;

    ApiAccess(Environment environment, Sessions sessions) {
        this.environment = environment;
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!(handler instanceof HandlerMethod method)) return true;
                response.setHeader("Cache-Control", "no-store");
                String path = request.getServletPath();
                String verb = request.getMethod();
                boolean safe = "GET".equals(verb) || "HEAD".equals(verb);
                if (("/api/health".equals(path) || "/api/auth/csrf".equals(path)) && safe) return true;
                if (environment.matchesProfiles("local-practice") && method.getBeanType() == PracticeController.class) {
                    return true;
                }
                boolean login = "/api/auth/login".equals(path) && "POST".equals(verb);
                if (!login) sessions.requireActive(request);
                if (!safe || method.getBeanType() == AccountDeletionController.class) sessions.verifyCsrf(request);
                if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                    throw new PracticeError(403, "FORBIDDEN");
                }
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}
