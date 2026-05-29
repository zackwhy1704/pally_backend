package com.pally.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Threads a request-id through every log line for a single request
 * (via SLF4J MDC) and echoes it back on the response so a Flutter
 * Sentry/Logcat error can be matched to the exact server logs.
 *
 * <p>Runs BEFORE the JWT filter so an auth-rejected request still
 * carries a requestId in its 401 log. {@code userId} is added later
 * (from the auth context) once the JWT filter populates it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8)
                : sanitize(incoming);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
            // Fill in the userId AFTER the chain in case the auth filter
            // populated it mid-flight; useful for the access-log line
            // that Tomcat / Spring writes after the body completes.
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof String pid
                    && !pid.isBlank()) {
                MDC.put(MDC_USER_ID, pid);
            }
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    /// Guard against header injection / log forging via the inbound id.
    private String sanitize(String s) {
        StringBuilder out = new StringBuilder(Math.min(s.length(), 32));
        for (int i = 0; i < s.length() && out.length() < 32; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.length() == 0 ? "anon" : out.toString();
    }
}
