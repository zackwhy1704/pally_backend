package com.pally.api.block;

import com.pally.domain.block.BlockedUserRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User blocking (App Store Guideline 1.2).
 *
 * <p>Blocking is a property of the VIEWER, not of a group, so it lives here
 * rather than under {@code /groups}: a student who blocks a classmate should stop
 * seeing them in every shared group at once, not have to repeat it per group.
 *
 * <p>Report and block are SEPARATE mechanisms and both are independently
 * reachable — Guideline 1.2 requires each to exist. Reporting is
 * {@code POST /groups/{groupId}/report} (unchanged); this endpoint does not
 * report, and reporting does not block.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/blocks")
@RequiredArgsConstructor
public class BlockController {

    private final BlockedUserRepository blockedUserRepo;

    /** Who has the caller blocked. Backs the manage-blocks screen. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal String userId) {
        List<Map<String, Object>> out = blockedUserRepo.listBlocked(userId).stream()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("userId", b.userId());
                    m.put("displayName", b.displayName());
                    m.put("blockedAt", b.blockedAt().toString());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PostMapping("/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> block(
            @AuthenticationPrincipal String userId,
            @PathVariable String targetUserId) {
        if (userId.equals(targetUserId)) {
            // Not merely silly: self-blocking would hide the student's OWN shared
            // notes from themselves, with no obvious way to work out why.
            throw new BusinessException("You can't block yourself", 400);
        }
        blockedUserRepo.block(userId, targetUserId);
        log.info("[Block] user={} blocked user={}", userId, targetUserId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Unblock. Blocking MUST be reversible — a mis-tap should not permanently cost
     * a student a classmate's study notes with no recourse.
     */
    @DeleteMapping("/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> unblock(
            @AuthenticationPrincipal String userId,
            @PathVariable String targetUserId) {
        blockedUserRepo.unblock(userId, targetUserId);
        log.info("[Block] user={} unblocked user={}", userId, targetUserId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
