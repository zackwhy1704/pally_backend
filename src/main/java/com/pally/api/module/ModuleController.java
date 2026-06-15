package com.pally.api.module;

import com.pally.api.module.dto.SubmitModuleAnswersRequest;
import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.module.ModuleService;
import com.pally.domain.module.NarrationService;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.ModuleNarration;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.DurationClamp;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
    private final NarrationService narrationService;
    private final AssignmentService assignmentService;

    /**
     * Generate modules for all wiki pages. Idempotent — skips existing slugs.
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> generateModules(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        log.info("[Module] Generate modules request user={} avatar={}", userId, avatarId);
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

    // ── Narration endpoints ──────────────────────────────────────────────

    /**
     * Triggers async narration generation for a module.
     * Returns 202 Accepted with the narration ID.
     */
    @PostMapping("/{moduleId}/narration/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String voiceId = (body != null) ? body.getOrDefault("voiceId", "default") : "default";
        log.info("[Narration] Generate request user={} module={} voice={}", userId, moduleId, voiceId);

        String narrationId = narrationService.generateAsync(moduleId, voiceId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("narrationId", narrationId);
        response.put("status", "GENERATING");

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(response, null, 202));
    }

    /**
     * Returns narration status and segments if READY.
     */
    @GetMapping("/{moduleId}/narration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String moduleId
    ) {
        return narrationService.get(moduleId)
                .map(n -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("id", n.getId());
                    response.put("status", n.getStatus());
                    response.put("voiceId", n.getVoiceId());
                    response.put("totalDurationMs", n.getTotalDurationMs());
                    try {
                        response.put("segments", new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(n.getSegmentsJson(), List.class));
                    } catch (Exception e) {
                        response.put("segments", List.of());
                    }
                    return ResponseEntity.ok(ApiResponse.success(response));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Narration not found for this module", 404)));
    }
}
