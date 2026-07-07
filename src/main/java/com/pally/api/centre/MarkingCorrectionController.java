package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.marking.MarkingCorrectionService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Teacher visibility + removal for captured marking corrections (Part 4 damper).
 * The teacher sees what the marking assistant has learned from their corrections
 * and can remove a bad one so it stops grounding future AI drafts.
 *
 * <p>Auth mirrors the sibling {@code MarkingReferenceController}: staff of the org
 * only, and the class must belong to the org.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/marking-corrections")
@RequiredArgsConstructor
public class MarkingCorrectionController {

    private final CentreAccessService accessService;
    private final OrgClassRepository orgClassRepository;
    private final MarkingCorrectionService markingCorrectionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        return ResponseEntity.ok(ApiResponse.success(
                markingCorrectionService.listForClass(classId)));
    }

    @DeleteMapping("/{correctionId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String correctionId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        // Correction-in-class (cross-class IDOR guard) is enforced in the service.
        markingCorrectionService.remove(classId, correctionId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("removed", true)));
    }

    private void requireClass(String orgId, String classId) {
        String owningOrg = orgClassRepository.findOrganizationIdByClassId(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(owningOrg)) {
            throw new BusinessException("Class not in this organization", 403);
        }
    }
}
