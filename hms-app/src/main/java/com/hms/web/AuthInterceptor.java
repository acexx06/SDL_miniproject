package com.hms.web;

import com.hms.domain.Role;
import com.hms.domain.Session;
import com.hms.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/** Resolves the bearer token on each API request and enforces {@link RequireAuth}. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION = "hms.session";

    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireAuth rule = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireAuth.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireAuth.class);
        }
        String header = request.getHeader("Authorization");
        Session session = header != null && header.startsWith("Bearer ")
                ? auth.authenticate(header.substring(7).trim()) : null;
        if (session != null) {
            request.setAttribute(SESSION, session);
        }
        if (rule == null) {
            return true;
        }
        if (session == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Please log in");
        }
        Role[] allowed = rule.value();
        if (allowed.length > 0 && !Arrays.asList(allowed).contains(session.role())) {
            throw ApiException.forbidden();
        }
        return true;
    }
}
