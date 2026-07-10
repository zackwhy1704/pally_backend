package com.pally.domain.centre;

import com.pally.domain.content.OutputType;
import com.pally.domain.content.OutputValidator;
import com.pally.domain.module.ModuleContentItem;
import com.pally.domain.module.ModuleContentItemRepository;
import com.pally.domain.module.ModuleService;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final OutputValidator outputValidator;

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

        // Approve/edit door (rider): any write that LEAVES the item in — or MOVES it into —
        // a servable status must pass the same validator that gates generation. Otherwise a
        // teacher can approve a legacy-blank draft, or edit a live item into blankness, and it
        // reaches students. One rule, three doors (this covers status-flip AND content edit).
        if (!"REJECTED".equals(newStatus)) {
            ContentReviewPort.ContentItemView existing = reviewPort.findById(itemId);
            if (existing != null) {
                String resultingStatus = newStatus != null ? newStatus : existing.status();
                if (ModuleContentItemRepository.SERVABLE_STATUSES.contains(resultingStatus)) {
                    Optional<String> reason = validationReason(
                            existing.type(),
                            contentJson != null ? contentJson : existing.contentJson(),
                            answerJson != null ? answerJson : existing.answerJson());
                    if (reason.isPresent()) {
                        // 422: the teacher sees WHY, so they can fix the item, not retry blind.
                        throw new BusinessException(
                                "This item can't go live: " + reason.get(), 422);
                    }
                }
            }
        }

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

        // Validate every draft BEFORE flipping it live; approve only the ones that pass and
        // report the rest to the TEACHER (not the log) with the validator's reason — a silent
        // skip just recreates the "I approved everything, why is one missing?" mystery.
        List<Map<String, Object>> drafts = reviewPort.findDraftItemsByClass(classId);
        List<String> validIds = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Map<String, Object> d : drafts) {
            String itemId = str(d.get("itemId"));
            Optional<String> reason = validationReason(
                    str(d.get("type")), str(d.get("contentJson")), str(d.get("answerJson")));
            if (reason.isEmpty()) {
                validIds.add(itemId);
            } else {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("itemId", itemId);
                s.put("moduleTitle", d.getOrDefault("moduleTitle", ""));
                s.put("reason", reason.get());
                skipped.add(s);
            }
        }
        int approved = reviewPort.approveItems(classId, validIds);
        log.info("[Content] Bulk approve class={}: approved={} skipped={}",
                classId, approved, skipped.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvedCount", approved);
        result.put("skippedCount", skipped.size());
        result.put("skipped", skipped); // [{itemId, moduleTitle, reason}] for the teacher UI
        return result;
    }

    /** The single validation seam for the approve/edit boundary — same rules as generation. */
    private Optional<String> validationReason(String type, String contentJson, String answerJson) {
        ModuleContentItem probe = new ModuleContentItem();
        probe.setType(type);
        probe.setContentJson(contentJson);
        probe.setAnswerJson(answerJson);
        return outputValidator.explain(probe, OutputType.MODULE_ITEM);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
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
