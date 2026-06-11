package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.module.ModuleService;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centre content review + publishing lifecycle endpoints.
 * Also includes exam readiness endpoint.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}")
@RequiredArgsConstructor
@Slf4j
public class ContentReviewController {

    private static final Set<String> VALID_STATUSES = Set.of("DRAFT", "APPROVED", "LIVE", "ARCHIVED", "REJECTED");

    private final CentreAccessService accessService;
    private final OrgClassJpaRepository classRepo;
    private final ModuleContentItemJpaRepository itemRepo;
    private final ModuleService moduleService;

    /**
     * List content items with DRAFT status for review.
     */
    @GetMapping("/content/review")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listDraftContent(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        // Get all items and filter to DRAFT status
        List<ModuleContentItemJpaEntity> allItems = itemRepo.findAll();
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (ModuleContentItemJpaEntity item : allItems) {
            if ("DRAFT".equals(item.getStatus())) {
                drafts.add(toDto(item));
            }
        }

        return ResponseEntity.ok(ApiResponse.success(drafts));
    }

    /**
     * Update a content item: edit content, approve, reject, or archive.
     */
    @PatchMapping("/content/{itemId}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateContentItem(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        ModuleContentItemJpaEntity item = itemRepo.findById(itemId)
                .orElseThrow(() -> new BusinessException("Content item not found", 404));

        if (body.containsKey("status")) {
            String newStatus = body.get("status").toString();
            if (!VALID_STATUSES.contains(newStatus)) {
                throw new BusinessException("Invalid status: " + newStatus, 400);
            }
            // REJECTED → delete the item
            if ("REJECTED".equals(newStatus)) {
                itemRepo.delete(item);
                return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true)));
            }
            item.setStatus(newStatus);
        }

        if (body.containsKey("contentJson")) {
            item.setContentJson(body.get("contentJson").toString());
        }

        if (body.containsKey("answerJson")) {
            item.setAnswerJson(body.get("answerJson").toString());
        }

        itemRepo.save(item);
        return ResponseEntity.ok(ApiResponse.success(toDto(item)));
    }

    /**
     * Bulk approve all DRAFT items for this class.
     */
    @PostMapping("/content/approve-all")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveAll(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        List<ModuleContentItemJpaEntity> allItems = itemRepo.findAll();
        int approved = 0;
        for (ModuleContentItemJpaEntity item : allItems) {
            if ("DRAFT".equals(item.getStatus())) {
                item.setStatus("LIVE");
                itemRepo.save(item);
                approved++;
            }
        }

        log.info("[Content] Bulk approved {} items for class={}", approved, classId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("approvedCount", approved)));
    }

    /**
     * Class-wide exam readiness: per-concept average mastery and suggestions.
     */
    @GetMapping("/exam-readiness")
    public ResponseEntity<ApiResponse<Map<String, Object>>> examReadiness(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        Map<String, Object> readiness = moduleService.getClassExamReadiness(classId);
        return ResponseEntity.ok(ApiResponse.success(readiness));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private OrgClassJpaEntity requireClass(String orgId, String classId) {
        OrgClassJpaEntity cls = classRepo.findById(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(cls.getOrganizationId())) {
            throw new BusinessException("Class not in this organization", 403);
        }
        return cls;
    }

    private Map<String, Object> toDto(ModuleContentItemJpaEntity item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("moduleId", item.getModuleId());
        m.put("stage", item.getStage());
        m.put("type", item.getType());
        m.put("contentJson", item.getContentJson());
        m.put("answerJson", item.getAnswerJson());
        m.put("sortOrder", item.getSortOrder());
        m.put("status", item.getStatus());
        return m;
    }
}
