package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.CentreService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Centre (B2B) endpoints. The Flutter app consumes the student-side redeem/leave
 * routes; the rest serve the admin web dashboard.
 *
 * <p>Authorization: every {@code /organizations/{orgId}/*} call asserts the
 * caller IS the org's owner (via {@link CentreAccessService}, inside
 * {@link CentreService}). Thin HTTP layer: delegate → wrap in {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/centre")
@RequiredArgsConstructor
public class CentreController {

    private final CentreService centreService;

    @PostMapping("/redeem-enroll-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeem(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(centreService.redeem(userId, body)));
    }

    @PostMapping("/redeem-class-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeemClassCode(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(centreService.redeemClassCode(userId, body)));
    }

    @PostMapping("/leave-class")
    public ResponseEntity<ApiResponse<Map<String, Object>>> leaveClass(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(centreService.leaveClass(userId, body)));
    }

    @GetMapping("/organizations/{orgId}/roster")
    public ResponseEntity<ApiResponse<Map<String, Object>>> roster(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(
                ApiResponse.success(centreService.roster(userId, orgId, cohort, page, size)));
    }

    @GetMapping("/organizations/{orgId}/analytics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analytics(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort) {
        return ResponseEntity.ok(
                ApiResponse.success(centreService.analytics(userId, orgId, cohort)));
    }

    @GetMapping(value = "/organizations/{orgId}/export", produces = "text/csv")
    public ResponseEntity<String> export(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort,
            @RequestParam(defaultValue = "csv") String format) {
        String csv = centreService.exportCsv(userId, orgId, cohort, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition", "attachment; filename=\"roster.csv\"")
                .body(csv);
    }

    @PostMapping("/organizations/{orgId}/enroll-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mintCode(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(centreService.mintCode(userId, orgId, body)));
    }

    @PostMapping("/admin/organizations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createOrg(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Admin-Secret", required = false) String adminSecret) {
        return ResponseEntity.ok(ApiResponse.success(centreService.createOrg(body, adminSecret)));
    }

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> onboard(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(centreService.onboard(userId, body)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(centreService.me(userId)));
    }

    @GetMapping("/organizations/{orgId}/activity")
    public ResponseEntity<ApiResponse<Map<String, Object>>> activity(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String since) {
        return ResponseEntity.ok(
                ApiResponse.success(centreService.activity(userId, orgId, since)));
    }

    @PostMapping("/organizations/{orgId}/avatars/{avatarId}/mark-centre")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markCentre(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(
                ApiResponse.success(centreService.markCentre(userId, orgId, avatarId)));
    }
}
