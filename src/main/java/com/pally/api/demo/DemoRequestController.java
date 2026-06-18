package com.pally.api.demo;

import com.pally.domain.demo.DemoLeadService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/demo-request")
@RequiredArgsConstructor
public class DemoRequestController {

    // 3 demo submissions per hour per IP — stops spam without blocking legitimate interest.
    private static final int DEMO_LIMIT = 3;
    private static final long DEMO_WINDOW_MS = 60L * 60_000;

    private final DemoLeadService demoLeadService;
    private final SlidingWindowRateLimiter rateLimiter;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @Valid @RequestBody DemoRequestBody body,
            HttpServletRequest request) {

        String ip = clientIp(request);
        var r = rateLimiter.tryAcquire("demo:" + ip, DEMO_LIMIT, DEMO_WINDOW_MS);
        if (!r.allowed()) {
            throw new BusinessException(
                    "Too many requests. Try again in " + r.retryAfterSeconds() + "s.", 429);
        }

        String leadId = demoLeadService.submitLead(
                body.orgName(), body.contactName(), body.email(), body.phone(),
                body.segment(), body.estClasses(), body.estStudents());
        return ResponseEntity.ok(ApiResponse.success(Map.of("ok", true, "leadId", leadId)));
    }

    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        String real = request.getHeader("X-Real-IP");
        return (real != null && !real.isBlank()) ? real : request.getRemoteAddr();
    }
}
