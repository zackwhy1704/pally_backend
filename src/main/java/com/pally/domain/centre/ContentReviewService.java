package com.pally.domain.centre;

import com.pally.domain.module.ModuleService;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain service for centre content review and publishing lifecycle.
 * Zero imports from {@code infrastructure.*} or {@code jakarta.persistence.*}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentReviewService {

    private static final Set<String> VALID_STATUSES =
            Set.of("DRAFT", "APPROVED", "LIVE", "ARCHIVED", "REJECTED");

    private final CentreAccessService centreAccessService;
    private final OrgClassRepository orgClassRepository;
    private final ContentReviewPort reviewPort;
    private final ModuleService moduleService;

    // ── Access guard ─────────────────────────────────────────────────────────

    /**
     * Validates that the class exists and belongs to the organization, then
     * asserts staff-or-owner access. Throws {@link BusinessException} on any violation.
     */
    private void assertAccess(String userId, String orgId, String classId) {
        centreAccessService.ensureStaff(userId, orgId);
        String classOrgId = orgClassRepository.findOrganizationIdByClassId(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(classOrgId)) {
            throw new BusinessException("Class not in this organization", 403);
        }
    }

    // ── List draft content ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDraftContent(
            String userId, String orgId, String classId) {
        assertAccess(userId, orgId, classId);
        return reviewPort.findDraftItemsByClass(classId);
    }

    /**
     * Teacher READ-ONLY preview of a generated module's content (LEARN/TEST/PROVE),
     * so a teacher can see what a student will get BEFORE assigning it. Reuses the
     * SAME staff+class authorization as the rest of this service (never hand-rolled),
     * then delegates to a class-scoped module fetch that omits answer keys.
     */
    public List<Map<String, Object>> previewModule(
            String userId, String orgId, String classId, String moduleId) {
        assertAccess(userId, orgId, classId);
        return moduleService.getModulePreview(moduleId, classId);
    }

    // ── Update content item ──────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> updateContentItem(
            String userId, String orgId, String classId, String itemId,
            Map<String, Object> body) {
        assertAccess(userId, orgId, classId);

        String newStatus = null;
        if (body.containsKey("status")) {
            newStatus = body.get("status").toString();
            if (!VALID_STATUSES.contains(newStatus)) {
                throw new BusinessException("Invalid status: " + newStatus, 400);
            }
        }

        String contentJson = body.containsKey("contentJson")
                ? body.get("contentJson").toString() : null;
        String answerJson = body.containsKey("answerJson")
                ? body.get("answerJson").toString() : null;

        var command = new ContentReviewPort.UpdateItemCommand(
                classId, itemId, newStatus, contentJson, answerJson);
        ContentReviewPort.ContentItemView updated = reviewPort.updateItem(command);

        if (updated == null) {
            // Item was REJECTED → deleted
            return Map.of("deleted", true);
        }
        return toDto(updated);
    }

    // ── Bulk approve ─────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> approveAll(String userId, String orgId, String classId) {
        assertAccess(userId, orgId, classId);
        int approved = reviewPort.approveAllDraftsByClass(classId);
        log.info("[Content] Bulk approved {} items for class={}", approved, classId);
        return Map.of("approvedCount", approved);
    }

    // ── Exam readiness ───────────────────────────────────────────────────────

    public Map<String, Object> examReadiness(String userId, String orgId, String classId) {
        assertAccess(userId, orgId, classId);
        return moduleService.getClassExamReadiness(classId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> toDto(ContentReviewPort.ContentItemView item) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("itemId", item.id());
        m.put("moduleId", item.moduleId());
        m.put("stage", item.stage());
        m.put("type", item.type());
        m.put("contentJson", item.contentJson());
        m.put("answerJson", item.answerJson());
        m.put("sortOrder", item.sortOrder());
        m.put("status", item.status());
        // Groundedness gate (B3) flag payload — the Review tab shows the flagged
        // claim and the cited source line so the teacher adjudicates. Null = clean.
        m.put("verification", item.verificationJson());
        return m;
    }
}
