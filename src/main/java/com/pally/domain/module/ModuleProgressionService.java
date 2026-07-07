package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module PROGRESSION responsibility: the LEARN → TEST → PROVE → COMPLETE
 * lifecycle — list/detail, start/resume, submit + stage advance, results,
 * revision, mastery, and the PROVE certainty nudge. Split out of the former
 * god ModuleService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleProgressionService {

    private final LearningModuleRepository moduleRepository;
    private final ModuleContentItemRepository itemRepository;
    private final ModuleProgressRepository progressRepository;
    private final ModuleContentGenerator contentGenerator;
    private final ModuleProveEvaluator proveEvaluator;
    private final WikiRepository wikiRepository;
    private final ObjectMapper objectMapper;
    private final com.pally.domain.notification.MilestoneNotifier milestoneNotifier;
    private final com.pally.domain.progress.ActivityLogService activityLogService;
    private final com.pally.domain.progress.XpService xpService;
    private final AvatarRepository avatarRepository;
    private final CentreAccessService centreAccessService;
    private final com.pally.domain.weakness.WeaknessProfileService weaknessProfileService;
    private final GradingWeights gradingWeights;

    /// Flat XP awarded when a module is fully completed (reaches COMPLETE).
    private static final int MODULE_COMPLETE_XP = 25;

    /**
     * List all modules for an avatar with stage, mastery, and item counts.
     */
    public List<Map<String, Object>> listModules(String avatarId) {
        List<LearningModule> modules = moduleRepository.findByAvatarId(avatarId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (LearningModule module : modules) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", module.getId());
            m.put("title", module.getTitle());
            m.put("wikiSlug", module.getWikiPageSlug());
            m.put("stage", module.getStage());
            m.put("tier", module.getTier());
            m.put("masteryPct", module.getMasteryPct());

            Map<String, Integer> counts = new HashMap<>();
            counts.put("learn", itemRepository.countByModuleIdAndStage(
                    module.getId(), ModuleStage.LEARN.name()));
            counts.put("test", itemRepository.countByModuleIdAndStage(
                    module.getId(), ModuleStage.TEST.name()));
            counts.put("prove", itemRepository.countByModuleIdAndStage(
                    module.getId(), ModuleStage.PROVE.name()));
            m.put("itemCounts", counts);

            result.add(m);
        }
        return result;
    }

    /**
     * Read-only TEACHER preview of a module's items — a faithful student view
     * WITHOUT answer keys. The caller MUST have authorized the teacher (see
     * ContentReviewService.assertAccess); this method only enforces that the
     * module belongs to the given class — a cross-class/cross-org guard so a
     * staff member of one org cannot read another org's module by guessing its id.
     * answerJson is deliberately omitted (teachers preview the experience, and a
     * preview must never leak gradeable answer keys).
     */
    public List<Map<String, Object>> getModulePreview(String moduleId, String classId) {
        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        if (classId == null || !classId.equals(module.getClassId())) {
            throw new BusinessException("Module not found", 404); // wrong class → 404, no existence leak
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModuleContentItem item : itemRepository.findByModuleIdOrderBySortOrder(moduleId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("stage", item.getStage());
            m.put("type", item.getType());
            m.put("contentJson", item.getContentJson());
            m.put("sortOrder", item.getSortOrder());
            out.add(m); // NO answerJson
        }
        return out;
    }

    /**
     * Get module detail with all items and progress for the current user.
     */
    public Map<String, Object> getModuleDetail(String moduleId, String userId) {
        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        assertModuleAccess(module, userId);

        List<ModuleContentItem> items =
                itemRepository.findByModuleIdOrderBySortOrder(moduleId);
        List<ModuleProgress> progress =
                progressRepository.findByModuleIdAndUserId(moduleId, userId);

        Map<String, ModuleProgress> progressMap = new HashMap<>();
        for (ModuleProgress p : progress) {
            progressMap.put(p.getItemId(), p);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", module.getId());
        result.put("title", module.getTitle());
        result.put("wikiSlug", module.getWikiPageSlug());
        result.put("stage", module.getStage());
        result.put("masteryPct", module.getMasteryPct());
        result.put("tier", module.getTier());

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (ModuleContentItem item : items) {
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("id", item.getId());
            itemMap.put("stage", item.getStage());
            itemMap.put("type", item.getType());
            itemMap.put("contentJson", item.getContentJson());
            itemMap.put("sortOrder", item.getSortOrder());
            itemMap.put("tierRequired", item.getTierRequired());

            ModuleProgress prog = progressMap.get(item.getId());
            if (prog != null) {
                itemMap.put("completed", prog.getCompletedAt() != null);
                itemMap.put("score", prog.getScore());
                itemMap.put("responseJson", prog.getResponseJson());
            } else {
                itemMap.put("completed", false);
            }

            itemList.add(itemMap);
        }
        result.put("items", itemList);
        // C3 — student-facing trust marker. A centre module is "teacher-reviewed"
        // when a teacher has approved its content (items APPROVED/LIVE, none DRAFT).
        // Personal content (no class) has no teacher, so it never gets the badge.
        boolean teacherReviewed = module.getClassId() != null && !items.isEmpty()
                && items.stream().allMatch(i ->
                        "APPROVED".equals(i.getStatus()) || "LIVE".equals(i.getStatus()));
        result.put("teacherReviewed", teacherReviewed);
        return result;
    }

    /**
     * Start or resume a module. If stage=PROVE and no prove items exist,
     * generates them adaptively based on TEST results.
     *
     * @return items for the current stage only
     */
    @Transactional
    public Map<String, Object> startModule(String moduleId, String userId) {
        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        assertModuleAccess(module, userId);

        ModuleStage currentStage = ModuleStage.valueOf(module.getStage());

        if (currentStage == ModuleStage.COMPLETE) {
            return startRevision(module, userId);
        }

        // If entering PROVE and no prove items exist, generate them
        if (currentStage == ModuleStage.PROVE) {
            int proveCount = itemRepository.countByModuleIdAndStage(
                    moduleId, ModuleStage.PROVE.name());
            if (proveCount == 0) {
                generateProveItemsAdaptively(module, userId);
            }
        }

        List<ModuleContentItem> stageItems =
                itemRepository.findByModuleIdAndStageOrderBySortOrder(
                        moduleId, currentStage.name());

        Map<String, Object> result = new HashMap<>();
        result.put("moduleId", module.getId());
        result.put("stage", currentStage.name());
        result.put("items", stageItems.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            // stage is required by the mobile ModuleContentItem parser — the
            // detail (getModuleDetail) path already includes it; the start path
            // must too, or every item fails to parse and the lesson shows the
            // generic "Something went wrong loading this lesson" error.
            m.put("stage", item.getStage());
            m.put("type", item.getType());
            m.put("contentJson", item.getContentJson());
            m.put("sortOrder", item.getSortOrder());
            // Include answer_json only for LEARN items (they're not secret)
            if (ModuleStage.LEARN.name().equals(item.getStage())) {
                m.put("answerJson", item.getAnswerJson());
            }
            return m;
        }).toList());

        return result;
    }

    /**
     * Submit answers for the current stage. Evaluates PROVE answers via
     * ProveEvaluator. Records progress. Advances stage when all items answered.
     */
    @Transactional
    public Map<String, Object> submitAnswers(
            String moduleId, String userId,
            List<Map<String, String>> submissions) {
        return submitAnswers(moduleId, userId, submissions, 0);
    }

    /// Same as {@link #submitAnswers(String, String, List)} but records the
    /// (already-clamped) wall-clock seconds the kid spent on this stage so the
    /// weekly minutes chart is honest. When a stage completes we log a single
    /// activity row carrying this duration; full module completion also awards
    /// a small flat XP.
    @Transactional
    public Map<String, Object> submitAnswers(
            String moduleId, String userId,
            List<Map<String, String>> submissions,
            int durationSeconds) {

        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        assertModuleAccess(module, userId);

        ModuleStage currentStage = ModuleStage.valueOf(module.getStage());
        if (currentStage == ModuleStage.COMPLETE) {
            throw new BusinessException("Module already completed", 400);
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, String> sub : submissions) {
            String itemId = sub.get("itemId");
            String response = sub.get("response");

            if (itemId == null) {
                throw new BusinessException("itemId is required in each submission", 400);
            }

            ModuleContentItem item = itemRepository.findById(itemId)
                    .orElseThrow(() -> new BusinessException("Item not found: " + itemId, 404));

            // Stage enforcement: only accept items for the current stage
            if (!item.getStage().equals(currentStage.name())) {
                throw new BusinessException(
                        "Item " + itemId + " belongs to stage " + item.getStage()
                                + " but current stage is " + currentStage.name(), 400);
            }

            // Record progress (upsert)
            ModuleProgress progress = progressRepository
                    .findByModuleIdAndUserIdAndItemId(moduleId, userId, itemId)
                    .orElseGet(() -> {
                        ModuleProgress p = new ModuleProgress();
                        p.setId(IdGenerator.newId());
                        p.setModuleId(moduleId);
                        p.setUserId(userId);
                        p.setItemId(itemId);
                        p.setStage(currentStage.name());
                        return p;
                    });

            progress.setResponseJson(response);
            progress.setCompletedAt(Instant.now());

            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("itemId", itemId);

            if (currentStage == ModuleStage.PROVE) {
                // Open-ended (Tier 2): the system NEVER asserts a correctness score.
                // Run the evaluator for FEEDBACK ONLY; its score does NOT feed
                // mastery. This row stays UNGRADED (score NULL) — never a false 0 —
                // until the student self-assesses (a separate low-trust SELF_REPORT
                // signal via the self-report endpoint).
                ModuleProveEvaluator.ProveResult evalResult =
                        proveEvaluator.evaluateAnswer(item, response);
                progress.setScore(null);
                progress.setSignalType(GradingSignal.UNGRADED);

                // Extract targetConcept + reference answer from answer_json (for the
                // self-assessment display).
                String referenceAnswer = null;
                try {
                    var answerNode = objectMapper.readTree(item.getAnswerJson());
                    progress.setTargetConcept(com.pally.shared.util.TextClamp.toCodePoints(
                            answerNode.path("targetConcept").asText(null), 255));
                    referenceAnswer = item.getAnswerJson();
                } catch (Exception ignored) {
                    // non-critical
                }

                itemResult.put("graded", false);            // no system-asserted score
                itemResult.put("feedback", evalResult.feedback());
                itemResult.put("referenceAnswer", referenceAnswer);
                itemResult.put("selfAssess", true);         // client shows Yes/Partly/No
            } else if (currentStage == ModuleStage.TEST) {
                // The server NEVER trusts a client-computed score (spoofable — it was
                // the client-authoritative grading hole). We do not yet server-grade
                // the raw choice for these types, so record NO trustworthy signal
                // (UNGRADED, score NULL) — an ungraded item asserts nothing and never
                // becomes a false 0. (Deterministic server-grading of the raw choice
                // is the Phase-1 follow-up, pending the client raw-choice contract.)
                progress.setScore(null);
                progress.setSignalType(GradingSignal.UNGRADED);
                try {
                    var respNode = objectMapper.readTree(response);
                    progress.setTargetConcept(com.pally.shared.util.TextClamp.toCodePoints(
                            respNode.path("concept").asText(null), 255));
                } catch (Exception ignored) {
                    // response may be a plain string, not JSON — fine, no concept.
                }
                // Reveal the correct answer so the client can show feedback (unchanged).
                itemResult.put("answerJson", item.getAnswerJson());
            } else {
                // LEARN: completion marker (not a graded signal).
                progress.setScore(BigDecimal.ONE);
            }

            progressRepository.save(progress);
            results.add(itemResult);
        }

        // Check if all items in this stage are answered → advance
        int totalInStage = itemRepository.countByModuleIdAndStage(
                moduleId, currentStage.name());
        int completedInStage = progressRepository.countByModuleIdAndUserIdAndStage(
                moduleId, userId, currentStage.name());

        boolean stageComplete = completedInStage >= totalInStage;
        ModuleStage nextStage = null;

        if (stageComplete) {
            nextStage = currentStage.next();
            int moduleXp = 0;
            if (nextStage != null) {
                module.setStage(nextStage.name());
                if (nextStage == ModuleStage.COMPLETE) {
                    module.setCompletedAt(Instant.now());
                    // Calculate mastery from PROVE scores
                    updateMastery(module, userId);
                    // Weakness head (pilot, flag-gated): debounced async recompile
                    // of the student's weakness brain. Only enqueue when enabled;
                    // never let it affect module completion.
                    if (weaknessProfileService.isEnabled()) {
                        try {
                            weaknessProfileService.onMasteryUpdated(
                                    userId, module.getAvatarId());
                        } catch (Exception ignored) {
                            // async dispatch is best-effort; module result stands
                        }
                    }
                    // Award a small flat XP for completing the whole module.
                    try {
                        xpService.awardFlat(userId, MODULE_COMPLETE_XP);
                        moduleXp = MODULE_COMPLETE_XP;
                    } catch (Exception e) {
                        log.warn("[Module] XP award failed module={}: {}",
                                moduleId, e.getMessage());
                    }
                    // Notify parent of module completion
                    try {
                        double mastery = module.getMasteryPct() != null
                                ? module.getMasteryPct().doubleValue() / 100.0
                                : 0.0;
                        milestoneNotifier.onModuleCompleted(
                                userId, module.getTitle(), mastery);
                    } catch (Exception e) {
                        log.warn("[Module] push notification failed module={}: {}",
                                moduleId, e.getMessage());
                    }
                }
                moduleRepository.save(module);
                log.info("[Module] Advanced module={} from {} to {}",
                        moduleId, currentStage.name(), nextStage.name());
            }
            // Log study time for this completed stage so the weekly minutes
            // chart reflects module work. Best-effort inside ActivityLogService.
            activityLogService.log(userId, module.getAvatarId(),
                    com.pally.domain.progress.ActivityLogService.TYPE_QUIZ,
                    durationSeconds, moduleXp);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("results", results);
        response.put("stageComplete", stageComplete);
        response.put("nextStage", nextStage != null ? nextStage.name() : null);
        return response;
    }

    /**
     * Full results with per-concept mastery breakdown.
     */
    public Map<String, Object> getResults(String moduleId, String userId) {
        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        assertModuleAccess(module, userId);

        List<ModuleProgress> allProgress =
                progressRepository.findByModuleIdAndUserId(moduleId, userId);

        Map<String, List<ModuleProgress>> byStage = new HashMap<>();
        for (ModuleProgress p : allProgress) {
            byStage.computeIfAbsent(p.getStage(), k -> new ArrayList<>()).add(p);
        }

        // Concept mastery breakdown from PROVE results
        List<Map<String, Object>> conceptMastery = new ArrayList<>();
        List<ModuleProgress> proveProgress =
                byStage.getOrDefault(ModuleStage.PROVE.name(), List.of());
        for (ModuleProgress p : proveProgress) {
            Map<String, Object> cm = new HashMap<>();
            cm.put("concept", p.getTargetConcept());
            cm.put("score", p.getScore());
            conceptMastery.add(cm);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("moduleId", module.getId());
        result.put("title", module.getTitle());
        result.put("stage", module.getStage());
        result.put("masteryPct", module.getMasteryPct());
        result.put("completedAt", module.getCompletedAt());
        result.put("conceptMastery", conceptMastery);

        // Stage-level summary
        Map<String, Object> stageSummary = new HashMap<>();
        for (ModuleStage s : ModuleStage.values()) {
            if (s == ModuleStage.COMPLETE) continue;
            List<ModuleProgress> stageProgress =
                    byStage.getOrDefault(s.name(), List.of());
            int total = itemRepository.countByModuleIdAndStage(moduleId, s.name());
            int completed = stageProgress.size();
            double avgScore = stageProgress.stream()
                    .filter(p -> p.getScore() != null)
                    .mapToDouble(p -> p.getScore().doubleValue())
                    .average()
                    .orElse(0.0);
            stageSummary.put(s.name(), Map.of(
                    "total", total,
                    "completed", completed,
                    "averageScore", avgScore));
        }
        result.put("stageSummary", stageSummary);

        return result;
    }

    /**
     * Revision mode: when a COMPLETE module is started again, set it back to PROVE,
     * generate fresh PROVE questions, and return them. Old progress is preserved for
     * trend analysis; the module stays COMPLETE after the new round updates mastery.
     */
    @Transactional
    public Map<String, Object> startRevision(LearningModule module, String userId) {
        log.info("[Module] Starting revision for module={} user={}", module.getId(), userId);

        // Set back to PROVE stage for fresh questions
        module.setStage(ModuleStage.PROVE.name());
        module.setCompletedAt(null);
        moduleRepository.save(module);

        // Generate fresh PROVE items (old ones are kept — new sort_order offsets them)
        generateProveItemsAdaptively(module, userId);

        // Return the new PROVE items
        List<ModuleContentItem> proveItems =
                itemRepository.findByModuleIdAndStageOrderBySortOrder(
                        module.getId(), ModuleStage.PROVE.name());

        Map<String, Object> result = new HashMap<>();
        result.put("moduleId", module.getId());
        result.put("stage", ModuleStage.PROVE.name());
        result.put("revision", true);
        result.put("items", proveItems.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            // stage is required by the mobile parser (see startModule above).
            m.put("stage", item.getStage());
            m.put("type", item.getType());
            m.put("contentJson", item.getContentJson());
            m.put("sortOrder", item.getSortOrder());
            return m;
        }).toList());

        return result;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /// IDOR guard for module-by-id endpoints: the caller must either own the
    /// module's avatar (personal modules) OR be an active member of the module's
    /// class (centre students share class modules). Otherwise 404 — so a stranger
    /// can't read another user's module or mutate its progress by guessing an id.
    private void assertModuleAccess(LearningModule module, String userId) {
        boolean ownsAvatar = module.getAvatarId() != null
                && avatarRepository.existsByIdAndUserId(module.getAvatarId(), userId);
        boolean enrolledInClass = centreAccessService.isActiveClassMember(
                userId, module.getClassId());
        if (!ownsAvatar && !enrolledInClass) {
            throw new BusinessException("Module not found", 404);
        }
    }

    /// Maps a PROVE score to a certainty delta and applies it to the module's
    /// wiki page. The PROVE question's free-text {@code targetConcept} is a
    /// sub-concept label (not a wiki slug), so the page that actually owns this
    /// proof is the module's {@code wikiPageSlug} — that's the slug we nudge.
    /// Returns the delta applied (0.0 when the score is in the neutral band or
    /// the slug is missing) so callers/tests can assert behaviour.
    double adjustCertaintyForProve(LearningModule module, double score) {
        String slug = module.getWikiPageSlug();
        if (slug == null || slug.isBlank()) return 0.0;

        double delta;
        if (score >= 0.8) {
            delta = 0.05;
        } else if (score <= 0.3) {
            delta = -0.08;
        } else {
            delta = 0.0;
        }
        if (delta == 0.0) return 0.0; // neutral band — skip the DB write entirely

        try {
            wikiRepository.adjustCertainty(
                    module.getAvatarId(), List.of(slug), delta);
        } catch (Exception e) {
            // Best-effort signal — never fail a PROVE submission over a certainty nudge.
            log.warn("[Module] certainty adjust failed slug={} delta={}: {}",
                    slug, delta, e.getMessage());
        }
        return delta;
    }

    private void generateProveItemsAdaptively(LearningModule module, String userId) {
        // Get TEST results to analyze weaknesses
        List<ModuleProgress> testProgress =
                progressRepository.findByModuleIdAndUserId(module.getId(), userId)
                        .stream()
                        .filter(p -> ModuleStage.TEST.name().equals(p.getStage()))
                        .toList();

        WikiPage page = wikiRepository
                .findByAvatarIdAndSlug(module.getAvatarId(), module.getWikiPageSlug())
                .orElse(null);

        if (page == null) {
            log.warn("[Module] Wiki page not found for module={} slug={}",
                    module.getId(), module.getWikiPageSlug());
            return;
        }

        contentGenerator.generateProveQuestions(module, page, testProgress, module.getTier());
    }

    /**
     * Records a student's self-assessment (YES/PARTLY/NO) of their open-ended
     * PROVE answer as a low-trust SELF_REPORT signal. Additive + non-blocking:
     * the PROVE item already completed on submit (count-based stage advance), so
     * this only upgrades the mastery signal from UNGRADED → SELF_REPORT. If the
     * module already completed, mastery is recomputed so the signal is reflected.
     */
    @Transactional
    public Map<String, Object> submitSelfReport(
            String moduleId, String userId, String itemId, SelfReport selfReport) {
        LearningModule module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        assertModuleAccess(module, userId);

        ModuleContentItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException("Item not found: " + itemId, 404));
        if (!ModuleStage.PROVE.name().equals(item.getStage())) {
            throw new BusinessException("Self-assessment is only for PROVE items", 400);
        }

        ModuleProgress progress = progressRepository
                .findByModuleIdAndUserIdAndItemId(moduleId, userId, itemId)
                .orElseThrow(() -> new BusinessException(
                        "Answer this item before self-assessing", 400));

        progress.setScore(BigDecimal.valueOf(selfReport.score()));
        progress.setSignalType(GradingSignal.SELF_REPORT);
        progressRepository.save(progress);

        // The self-report weakly nudges wiki certainty (replaces the removed
        // system-asserted LLM nudge).
        adjustCertaintyForProve(module, selfReport.score());

        // If the module already completed (count-based advance), recompute mastery
        // so the upgraded signal is reflected, and refresh the weakness brain.
        if (ModuleStage.COMPLETE.name().equals(module.getStage())) {
            updateMastery(module, userId);
            moduleRepository.save(module);
            if (weaknessProfileService.isEnabled()) {
                try {
                    weaknessProfileService.onMasteryUpdated(userId, module.getAvatarId());
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("itemId", itemId);
        res.put("signalType", GradingSignal.SELF_REPORT.name());
        res.put("recorded", true);
        return res;
    }

    private void updateMastery(LearningModule module, String userId) {
        List<ModuleProgress> proveProgress =
                progressRepository.findByModuleIdAndUserId(module.getId(), userId)
                        .stream()
                        .filter(p -> ModuleStage.PROVE.name().equals(p.getStage()))
                        .toList();

        // Trust-weighted mastery (Tier 3): only GRADED signals count. An UNGRADED
        // row (score NULL — eval error / skip / not-yet-self-assessed) contributes
        // NOTHING, so a failure never becomes a false 0. A SELF_REPORT moves mastery
        // gradingWeights.selfReportWeight (0.25x) as much as a DETERMINISTIC grade —
        // normalised by COUNT so the trust weight scales the magnitude, not just the
        // blend. With NO trustworthy signal, mastery is left UNCHANGED (errors/skips
        // don't move it at all).
        double weightedSum = 0;
        int gradedCount = 0;
        for (ModuleProgress p : proveProgress) {
            if (p.getScore() == null || p.getSignalType() == GradingSignal.UNGRADED) continue;
            double w = gradingWeights.weightFor(p.getSignalType());
            if (w <= 0) continue;
            weightedSum += w * p.getScore().doubleValue();
            gradedCount++;
        }

        if (gradedCount == 0) return; // no trustworthy signal — leave mastery unchanged

        double avg = weightedSum / gradedCount;
        module.setMasteryPct(BigDecimal.valueOf(avg * 100)
                .setScale(2, RoundingMode.HALF_UP));
    }
}
