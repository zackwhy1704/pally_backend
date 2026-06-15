package com.pally.api.parent;

import com.pally.domain.parent.dto.ParentDashboardResponse;
import com.pally.domain.parent.dto.WeeklyReportDetail;
import com.pally.domain.parent.dto.WeeklyReportSummary;
import com.pally.domain.parent.ParentService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/parent")
@RequiredArgsConstructor
public class ParentController {

    private final ParentService parentService;

    // ── PIN management ─────────────────────────────────────────────────────

    @GetMapping("/pin/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pinStatus(
            @AuthenticationPrincipal String userId) {
        boolean hasPin = parentService.hasPinSet(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("hasPin", hasPin)));
    }

    @PostMapping("/pin/set")
    public ResponseEntity<ApiResponse<Void>> setPin(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String pin = body.get("pin");
        if (pin == null || !pin.matches("\\d{4,6}")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("PIN must be 4-6 digits", 400));
        }
        parentService.setPin(userId, pin);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/pin/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPin(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String pin = body.get("pin");
        if (pin == null || !pin.matches("\\d{4,6}")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("PIN must be 4-6 digits", 400));
        }
        ParentService.VerifyPinResult r = parentService.verifyPin(userId, pin);
        if (r.lockedOut()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "verified", false,
                    "firstTimeSetup", false,
                    "lockedOut", true,
                    "retryAfterSeconds", r.retryAfterSeconds())));
        }
        if (r.firstTimeSetup()) {
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("verified", true, "firstTimeSetup", true)));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "verified", r.verified(),
                "firstTimeSetup", false,
                "attemptsRemaining", r.attemptsRemaining())));
    }

    @PostMapping("/pin/reset")
    public ResponseEntity<ApiResponse<Void>> resetPin(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String newPin = body.get("newPin");
        if (newPin == null || !newPin.matches("\\d{4,6}")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("PIN must be 4-6 digits", 400));
        }
        String password = body.get("password");
        parentService.resetPin(userId, password, newPin);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Dashboard ───────────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<ParentDashboardResponse>> getDashboard(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(parentService.getDashboard(userId)));
    }

    // ── Weekly reports ──────────────────────────────────────────────────────

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listReports(
            @AuthenticationPrincipal String userId) {
        List<WeeklyReportSummary> reports = parentService.listReports(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("reports", reports)));
    }

    @GetMapping("/reports/{weekId}/share-text")
    public ResponseEntity<ApiResponse<Map<String, String>>> getShareText(
            @AuthenticationPrincipal String userId,
            @PathVariable String weekId) {
        Map<String, String> result = parentService.getShareText(userId, weekId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/reports/{weekId}")
    public ResponseEntity<ApiResponse<WeeklyReportDetail>> getReport(
            @AuthenticationPrincipal String userId,
            @PathVariable String weekId) {
        try {
            WeeklyReportDetail detail = parentService.getReport(userId, weekId);
            return ResponseEntity.ok(ApiResponse.success(detail));
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    "Invalid weekId, expected yyyy-Www (e.g. 2026-W22)", 400);
        }
    }

    // ── Screen time ─────────────────────────────────────────────────────────

    @PostMapping("/screen-time")
    public ResponseEntity<ApiResponse<Void>> setScreenTime(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Object> body) {
        Boolean enabled = (Boolean) body.get("enabled");
        Number minutes = (Number) body.get("minutes");
        parentService.setScreenTime(userId, enabled, minutes);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
