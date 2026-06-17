package com.pally.api.centre;

import com.pally.domain.centre.OrgStaffService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Staff membership endpoints for multi-teacher centres.
 *
 * <p>GET  /api/v1/centre/{orgId}/staff          — list staff (owner or staff)
 * <p>POST /api/v1/centre/{orgId}/staff/invite   — invite teacher by email (owner-only)
 * <p>DELETE /api/v1/centre/{orgId}/staff/{uid}  — remove staff member (owner-only)
 */
@RestController
@RequestMapping("/api/v1/centre/{orgId}/staff")
@RequiredArgsConstructor
public class StaffController {

    private final OrgStaffService staffService;

    @GetMapping
    public ResponseEntity<ApiResponse<Object>> list(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(staffService.listStaff(userId, orgId)));
    }

    @PostMapping("/invite")
    public ResponseEntity<ApiResponse<Object>> invite(
            @PathVariable String orgId,
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Object> body) {
        String email = body == null ? null : (String) body.get("email");
        return ResponseEntity.ok(ApiResponse.success(staffService.inviteStaff(userId, orgId, email)));
    }

    @DeleteMapping("/{staffUserId}")
    public ResponseEntity<ApiResponse<Object>> remove(
            @PathVariable String orgId,
            @PathVariable String staffUserId,
            @AuthenticationPrincipal String userId) {
        staffService.removeStaff(userId, orgId, staffUserId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("removed", true)));
    }
}
