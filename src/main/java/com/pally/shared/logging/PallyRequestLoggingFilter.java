package com.pally.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;

@Component
public class PallyRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PallyRequestLoggingFilter.class);

    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "x-api-key", "cookie");

    // Skip actuator noise
    private static final Set<String> SKIP_PATHS = Set.of("/actuator/health", "/actuator/info");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SKIP_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        long start = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String userId = request.getHeader("X-User-Id");

        log.info("[{}] ──► {} {} | user={} | ip={}",
                requestId,
                request.getMethod(),
                request.getRequestURI() + formatQuery(request.getQueryString()),
                userId != null ? userId : "anonymous",
                getClientIp(request));

        if (log.isDebugEnabled()) {
            Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames != null && headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                String value = SENSITIVE_HEADERS.contains(name.toLowerCase())
                        ? "[REDACTED]"
                        : request.getHeader(name);
                log.debug("[{}]   Header: {}: {}", requestId, name, value);
            }
        }

        ContentCachingResponseWrapper responseWrapper =
                new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(request, responseWrapper);
        } finally {
            long duration = System.currentTimeMillis() - start;
            int status = responseWrapper.getStatus();

            if (status >= 500) {
                String body = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                log.error("[{}] ◄── {} {} {}ms | {}",
                        requestId, status, request.getRequestURI(), duration,
                        truncate(body, 500));
            } else if (status >= 400) {
                String body = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                log.warn("[{}] ◄── {} {} {}ms | {}",
                        requestId, status, request.getRequestURI(), duration,
                        truncate(body, 500));
            } else {
                log.info("[{}] ◄── {} {} {}ms",
                        requestId, status, request.getRequestURI(), duration);
            }

            responseWrapper.copyBodyToResponse();
        }
    }

    private String formatQuery(String query) {
        return query != null ? "?" + query : "";
    }

    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        return ip != null ? ip.split(",")[0].trim() : req.getRemoteAddr();
    }

    private String truncate(String s, int max) {
        if (s == null || s.isBlank()) return "(empty)";
        return s.length() > max ? s.substring(0, max) + "... [+" + (s.length() - max) + " chars]" : s;
    }
}
