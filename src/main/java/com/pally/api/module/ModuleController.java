package com.pally.api.module;

import com.pally.api.module.dto.SubmitModuleAnswersRequest;
import com.pally.api.module.dto.SelfReportRequest;
import com.pally.domain.module.SelfReport;
import com.pally.shared.exception.BusinessException;
import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.module.ModuleService;
import com.pally.domain.module.LearningModule;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.DurationClamp;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pally.domain.module.dto.MasteryAuditResponse;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the learning module pipeline.
 * All routes are scoped to an avatar.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/modules")
@RequiredArgsConstructor
@Slf4j
public class ModuleController {

    private final ModuleService moduleService;
    private final AssignmentService assignmentService;
    private final ConsentGuard consentGuard;
    private final com.pally.domain.module.MasteryAuditService masteryAuditService;

    /**
     * Generate modules for all wiki pages. Idempotent — skips existing slugs.
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> generateModules(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        log.info("[Module] Generate modules request user={} avatar={}", userId, avatarId);
        consentGuard.requireAiAllowed(userId); // PDPA/PDPC: gate AI module generation
        List<LearningModule> created = moduleService.generateModules(avatarId);

        List<Map<String, Object>> response = created.stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "title", m.getTitle(),
                        "wikiSlug", m.getWikiPageSlug(),
                        "stage", m.getStage(),
                        "tier", m.getTier()))
                .toList();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * List all modules for this avatar with stage, mastery, and item counts.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listModules(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        List<Map<String, Object>> modules = moduleService.listModules(avatarId);
        return ResponseEntity.ok(ApiResponse.success(modules));
    }

    /**
     * Get module detail with all items and progress for the current user.
     */
    @GetMapping("/{moduleId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getModuleDetail(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId
    ) {
        Map<String, Object> detail = moduleService.getModuleDetail(moduleId, userId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Start or resume a module. Returns current stage's items.
     * If stage=PROVE and no prove items exist, generates them adaptively.
     */
    @PostMapping("/{moduleId}/start")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startModule(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId
    ) {
        consentGuard.requireAiAllowed(userId); // PDPA/PDPC: gate adaptive AI item generation
        Map<String, Object> result = moduleService.startModule(moduleId, userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Submit answers for the current stage. Returns per-item results
     * and whether the stage advanced.
     */
    @PostMapping("/{moduleId}/submit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitAnswers(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId,
            @Valid @RequestBody SubmitModuleAnswersRequest request
    ) {
        consentGuard.requireAiAllowed(userId); // PDPA/PDPC: gate AI answer evaluation
        int durationSeconds = DurationClamp.clamp(request.durationSeconds());
        Map<String, Object> result = moduleService.submitAnswers(
                moduleId, userId, request.submissions(), durationSeconds);

        // After module submit, check if any active assignments are now fulfilled
        try {
            assignmentService.checkAndAdvanceCompletions(userId);
        } catch (Exception e) {
            log.warn("[Module] Assignment completion check failed for user={}: {}",
                    userId, e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Self-assessment of an open-ended PROVE answer (Tier 2). The system never
     * asserts correctness for open-ended work; the student self-reports
     * YES/PARTLY/NO after seeing the reference answer, recorded as a low-trust
     * SELF_REPORT signal. Additive + non-blocking (the item already completed on
     * submit).
     */
    @PostMapping("/{moduleId}/items/{itemId}/self-report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitSelfReport(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId,
            @PathVariable String itemId,
            @Valid @RequestBody SelfReportRequest request
    ) {
        SelfReport selfReport;
        try {
            selfReport = SelfReport.valueOf(request.selfReport().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("selfReport must be YES, PARTLY, or NO", 400);
        }
        Map<String, Object> result = moduleService.submitSelfReport(
                moduleId, userId, itemId, selfReport);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Full results with per-concept mastery breakdown.
     */
    @GetMapping("/{moduleId}/results")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getResults(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId
    ) {
        Map<String, Object> results = moduleService.getResults(moduleId, userId);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    /**
     * Auditable mastery: the module's mastery number alongside the evidence behind
     * it, broken down by trust tier. Read-only, self-scoped — {@code userId} comes
     * from the authenticated principal, never a path/query parameter, so there is
     * no caller-supplied student id to walk.
     */
    @GetMapping("/{moduleId}/mastery-audit")
    public ResponseEntity<ApiResponse<MasteryAuditResponse>> masteryAudit(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(masteryAuditService.audit(moduleId, userId)));
    }

}
