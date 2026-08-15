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
    private final com.pally.domain.learning.LearningEventRepository learningEventRepository;

    /// Flat XP awarded when a module is fully completed (reaches COMPLETE).
    private static final int MODULE_COMPLETE_XP = 25;

    /// Additive mirror into the unified learning_event stream. Best-effort:
    /// never lets a learning_event write failure affect the primary progress
    /// save. No event for UNGRADED (null score / null signalType) — mirrors
    /// module_progress's own "never a false 0" invariant.
    private void recordLearningEvent(ModuleProgress progress, String userId, String avatarId,
                                      com.pally.domain.learning.LearningEventSource source) {
        if (progress.getSignalType() == null || progress.getSignalType() == GradingSignal.UNGRADED) {
            return;
        }
        try {
            com.pally.domain.learning.LearningEventProvenance provenance =
                    progress.getSignalType() == GradingSignal.SELF_REPORT
                            ? com.pally.domain.learning.LearningEventProvenance.SELF_REPORT
                            : com.pally.domain.learning.LearningEventProvenance.VERIFIED_SERVER_GRADED;
            learningEventRepository.save(com.pally.domain.learning.LearningEvent.of(
                    userId, avatarId, source, provenance,
                    progress.getTargetConcept(), progress.getScore(), progress.getId()));
        } catch (Exception e) {
            log.warn("[LearningEvent] write failed (non-fatal): {}", e.getMessage());
        }
    }

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
            m.put("masteryPct", clampPct(module.getMasteryPct()));

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

        // SERVING: a student receives only servable-status items (LIVE/APPROVED) — never
        // DRAFT (mid-teacher-review), ARCHIVED, or reaper-quarantined/retired content.
        List<ModuleContentItem> items =
                itemRepository.findServableByModuleIdOrderBySortOrder(moduleId);
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
        result.put("masteryPct", clampPct(module.getMasteryPct()));
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
        //
        // DELIBERATELY reads the FULL set (findByModuleId*), NOT findServable*. The badge's
        // entire job is to detect an UNreviewed (DRAFT) item; computed over a servable-only
        // list — which is LIVE/APPROVED by construction — it would be always-true and
        // silently stop doing its only job. Do NOT "tidy" this onto findServable*.
        // (See ModuleContentItemRepository.SERVABLE_STATUSES.)
        List<ModuleContentItem> allItems =
                itemRepository.findByModuleIdOrderBySortOrder(moduleId);
        boolean teacherReviewed = module.getClassId() != null && !allItems.isEmpty()
                && allItems.stream().allMatch(i ->
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

        // SERVING (the PROVE-existence gate above stays UNFILTERED so it can't spuriously
        // regenerate): the student receives only servable-status items for this stage.
        List<ModuleContentItem> stageItems =
                itemRepository.findServableByModuleIdAndStageOrderBySortOrder(
                        moduleId, currentStage.name());

        return buildStageResponse(moduleId, currentStage, stageItems, false,
                module.getTitle(), module.getWikiPageSlug());
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
                        proveEvaluator.evaluateAnswer(item, response, module.getContentLanguage());
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
                // The server NEVER reads a client-supplied score (spoofable — the old
                // client-authoritative hole). HOT_TAKE is graded DETERMINISTICALLY
                // from the student's RAW CHOICE (AGREE/DISAGREE) against the persisted
                // key (answer_json.isTrue). Other TEST types have no discrete key →
                // UNGRADED (never trust the client). A legacy payload with no usable
                // raw choice → UNGRADED (no 400 for old clients).
                BigDecimal score = null;
                GradingSignal signal = GradingSignal.UNGRADED;
                if (ContentItemType.HOT_TAKE.name().equals(item.getType())) {
                    Boolean studentSaysTrue = parseHotTakeChoice(response);
                    Boolean key = parseHotTakeKey(item.getAnswerJson());
                    if (studentSaysTrue != null && key != null) {
                        score = studentSaysTrue.equals(key) ? BigDecimal.ONE : BigDecimal.ZERO;
                        signal = GradingSignal.DETERMINISTIC;
                    } else if (key == null) {
                        log.warn("[Module] HOT_TAKE item={} has no persisted key "
                                + "(reason=no-key) — UNGRADED", itemId);
                    } else {
                        log.info("[Module] HOT_TAKE item={} no usable raw choice "
                                + "(reason=no-rawChoice, legacy client) — UNGRADED", itemId);
                    }
                } else if (ContentItemType.SPOT_MISTAKE.name().equals(item.getType())) {
                    // SPOT_MISTAKE self-check (Tier 2, LOW-TRUST SELF_REPORT — NEVER
                    // machine grading, exactly like PROVE self-assessment). The student
                    // TYPED a diagnosis (persisted above as responseJson) and then
                    // self-assessed "were you right?": YES→1.0, NOT_QUITE→0.0, recorded
                    // at the identical 0.30 self-report trust weight. An ABSENT selfCheck
                    // (legacy client, or the honor-system reveal with no typed diagnosis)
                    // leaves the row UNGRADED null-signal — byte-for-byte the old
                    // behaviour, so legacy SM rows stay excluded from mastery.
                    Boolean right = parseSelfCheck(sub.get("selfCheck"));
                    if (right != null) {
                        score = right ? BigDecimal.ONE : BigDecimal.ZERO;
                        signal = GradingSignal.SELF_REPORT;
                    }
                }
                progress.setScore(score);
                progress.setSignalType(signal);
                // Provenance: copy the item's targetConcept onto the progress row (mirror
                // the PROVE extraction above), so adaptive PROVE gen's testSummary reads
                // real concept names ("Compliment bridging: 0.00"), never "unknown: 0.00".
                try {
                    var answerNode = objectMapper.readTree(item.getAnswerJson());
                    progress.setTargetConcept(com.pally.shared.util.TextClamp.toCodePoints(
                            answerNode.path("targetConcept").asText(null), 255));
                } catch (Exception ignored) {
                    // non-critical — legacy pre-tag item leaves targetConcept null
                }
                itemResult.put("answerJson", item.getAnswerJson());
                // `graded` is TRUE only for a system-asserted grade (DETERMINISTIC).
                // A SPOT_MISTAKE self-check is NEVER graded=true — it's a low-trust
                // self-report; expose it distinctly so the client never renders it as
                // a machine verdict.
                itemResult.put("graded", signal == GradingSignal.DETERMINISTIC);
                if (signal == GradingSignal.DETERMINISTIC) {
                    itemResult.put("correct", score.compareTo(BigDecimal.ONE) == 0);
                } else if (signal == GradingSignal.SELF_REPORT) {
                    itemResult.put("selfReported", true);
                }
            } else {
                // LEARN: completion marker (not a graded signal).
                progress.setScore(BigDecimal.ONE);
            }

            progressRepository.save(progress);
            recordLearningEvent(progress, userId, module.getAvatarId(),
                    com.pally.domain.learning.LearningEventSource.MODULE_TEST);
            results.add(itemResult);
        }

        // Check if all items in this stage are answered → advance. The denominator
        // is the SERVABLE count, NOT the unfiltered one: a non-servable row
        // (reaper-QUARANTINED/RETIRED, DRAFT) is never served to the student, so
        // counting it would strand them mid-stage — they answer every item they were
        // given, but completedInStage never reaches an unfiltered total that includes
        // rows they can't see (the advance-layer sibling of P1's serve-layer empty
        // guard; the reaper is the only thing that creates non-servable rows). A
        // stage with ZERO servable items is NOT complete — it's the transient
        // CONTENT_UPDATING state; never auto-advance past an empty stage.
        int servableInStage = itemRepository.countServableByModuleIdAndStage(
                moduleId, currentStage.name());
        int completedInStage = progressRepository.countByModuleIdAndUserIdAndStage(
                moduleId, userId, currentStage.name());

        boolean stageComplete = servableInStage > 0 && completedInStage >= servableInStage;
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
                        // null = no graded signal → notification omits the % (never
                        // a false "0% mastery" for an unassessed module).
                        Double mastery = module.getMasteryPct() != null
                                ? module.getMasteryPct().doubleValue() / 100.0
                                : null;
                        milestoneNotifier.onModuleCompleted(
                                userId, module.getTitle(), mastery,
                                isSelfReportOnly(moduleId, userId));
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
        result.put("masteryPct", clampPct(module.getMasteryPct()));
        result.put("completedAt", module.getCompletedAt());
        result.put("conceptMastery", conceptMastery);

        // Stage-level summary
        Map<String, Object> stageSummary = new HashMap<>();
        for (ModuleStage s : ModuleStage.values()) {
            if (s == ModuleStage.COMPLETE) continue;
            List<ModuleProgress> stageProgress =
                    byStage.getOrDefault(s.name(), List.of());
            // SERVABLE total (completion family — must match the submitAnswers advance
            // denominator) so a student who finished every served item reads N/N, not
            // N/N+1 because a reaper-RETIRED row still lingers in the unfiltered count.
            int total = itemRepository.countServableByModuleIdAndStage(moduleId, s.name());
            int completed = stageProgress.size();
            var avg = stageProgress.stream()
                    .filter(p -> p.getScore() != null)
                    .mapToDouble(p -> p.getScore().doubleValue())
                    .average();
            // null = no graded signal (all UNGRADED) — NOT 0%. Never a false 0.
            Map<String, Object> summary = new HashMap<>();
            summary.put("total", total);
            summary.put("completed", completed);
            summary.put("averageScore", avg.isPresent() ? avg.getAsDouble() : null);
            stageSummary.put(s.name(), summary);
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

        // Return the new PROVE items (SERVING → servable-only; the freshly generated
        // ones are LIVE, any non-servable older ones are correctly excluded).
        List<ModuleContentItem> proveItems =
                itemRepository.findServableByModuleIdAndStageOrderBySortOrder(
                        module.getId(), ModuleStage.PROVE.name());

        return buildStageResponse(module.getId(), ModuleStage.PROVE, proveItems, true,
                module.getTitle(), module.getWikiPageSlug());
    }

    /**
     * Builds the /start (and /revision) stage payload AND enforces the module-
     * PLAYABILITY invariant: a stage must never be entered with zero SERVABLE
     * items, because the student would have nothing to answer → the stage can
     * never advance (the advance denominator reads the UNFILTERED count), so the
     * lesson dead-ends on a benign-looking 200. When the servable list is empty
     * we attach a {@code contentStatus} so the client can react honestly instead
     * of retry-looping:
     *   • CONTENT_UPDATING  — items exist for this stage but none are currently
     *     servable (DRAFT / reaper-QUARANTINED mid-heal). Transient: the client
     *     bounces to Library, no strand, no re-POST spin.
     *   • CONTENT_UNAVAILABLE — no items at all for this stage (a genuine gap;
     *     generation guarantees ≥1, so this is a real anomaly worth surfacing).
     * The normal (non-empty) payload shape is unchanged — {@code contentStatus}
     * is additive and appears only in the previously-dead-end empty case.
     */
    private Map<String, Object> buildStageResponse(
            String moduleId, ModuleStage stage,
            List<ModuleContentItem> servableItems, boolean revision,
            String sourcePageTitle, String sourcePageSlug) {
        Map<String, Object> result = new HashMap<>();
        result.put("moduleId", moduleId);
        result.put("stage", stage.name());
        if (revision) {
            result.put("revision", true);
        }
        result.put("items", servableItems.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            // stage is required by the mobile ModuleContentItem parser — without
            // it every item fails to parse and the lesson shows the generic
            // "Something went wrong loading this lesson" error.
            m.put("stage", item.getStage());
            m.put("type", item.getType());
            m.put("contentJson", item.getContentJson());
            m.put("sortOrder", item.getSortOrder());
            // Provenance metadata (NOT answer content): the source page every item in this
            // module came from, so the client can show a "From your notes: {title}" chip
            // that taps through to the wiki page. Same for every item (one module = one page).
            m.put("sourcePageTitle", sourcePageTitle);
            m.put("sourcePageSlug", sourcePageSlug);
            // Include answer_json only for LEARN items (they're not secret).
            if (ModuleStage.LEARN.name().equals(item.getStage())) {
                m.put("answerJson", item.getAnswerJson());
            }
            // TEST reveal (field-filtered — least-leak by construction): attach ONLY the
            // non-secret fields the client's reveal panel renders, in a NEW `revealJson`
            // key (never overload the LEARN-only answerJson contract). SPOT_MISTAKE and
            // CHALLENGE are UNGRADED, so their reveals are explanations, safe to serve —
            // but CHALLENGE's `answer` (the model answer) is deliberately NOT served.
            // HOT_TAKE gets NOTHING: isTrue is the graded key and its explanation rides
            // the per-item submit response. Absent revealJson → the client keeps its
            // current blank-tolerant behaviour (back-compat with old servers).
            if (ModuleStage.TEST.name().equals(item.getStage())) {
                Map<String, Object> reveal = buildTestReveal(item);
                if (reveal != null && !reveal.isEmpty()) {
                    m.put("revealJson", reveal);
                }
            }
            // PROVE targeting metadata (SAFE — NOT the reference answer): the concept this
            // question targets and the student's PRIOR TEST score on it, so the client can
            // show a pre-answer badge ("Focusing on {concept} — this tripped you up"). The
            // reference/expectedKeyPoints answer is NEVER served here (rides the submit).
            if (ModuleStage.PROVE.name().equals(item.getStage())) {
                try {
                    var node = objectMapper.readTree(item.getAnswerJson());
                    String tc = node.path("targetConcept").asText(null);
                    if (tc != null && !tc.isBlank()) {
                        m.put("targetConcept", tc);
                    }
                    if (node.hasNonNull("priorScore")) {
                        m.put("priorScore", node.get("priorScore").asDouble());
                    }
                } catch (Exception ignored) {
                    // legacy pre-tag PROVE item — no targeting metadata
                }
            }
            return m;
        }).toList());

        if (servableItems.isEmpty()) {
            int total = itemRepository.countByModuleIdAndStage(moduleId, stage.name());
            String status = total > 0 ? "CONTENT_UPDATING" : "CONTENT_UNAVAILABLE";
            result.put("contentStatus", status);
            log.warn("[Module] stage {} served EMPTY for module={} (servable=0, total={}) "
                    + "→ {} — playability guard engaged (student bounced, not stranded)",
                    stage.name(), moduleId, total, status);
        }
        return result;
    }

    /// Field-filtered TEST reveal for the SERVE path: exactly the fields the client's
    /// reveal panel renders, and nothing more. HOT_TAKE → null (its verdict+explanation
    /// come from the graded submit response; isTrue must never be served — it's the
    /// spoofable key). CHALLENGE serves `explanation` ONLY (never `answer`, the model
    /// answer). Returns null when there's nothing safe to serve.
    private Map<String, Object> buildTestReveal(ModuleContentItem item) {
        String type = item.getType();
        if (ContentItemType.SPOT_MISTAKE.name().equals(type)) {
            return pickStringFields(item.getAnswerJson(), "errorDescription", "correctSolution");
        }
        if (ContentItemType.CHALLENGE.name().equals(type)) {
            return pickStringFields(item.getAnswerJson(), "explanation"); // NEVER "answer"
        }
        return null; // HOT_TAKE (and any unknown type): serve no reveal
    }

    /// Extracts ONLY the allow-listed string fields from a JSON-object string. A field
    /// not present is simply omitted; a malformed/blank source yields an empty map. It
    /// can NEVER return a field outside {@code fields}, so the serve payload cannot
    /// accidentally widen to a secret.
    private Map<String, Object> pickStringFields(String json, String... fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            var node = objectMapper.readTree(json);
            for (String f : fields) {
                if (node.hasNonNull(f)) out.put(f, node.path(f).asText());
            }
        } catch (Exception ignored) {
            // malformed answer_json → empty reveal (client falls back to blank)
        }
        return out;
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
        recordLearningEvent(progress, userId, module.getAvatarId(),
                com.pally.domain.learning.LearningEventSource.PROVE);

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

    /// Maps a HOT_TAKE raw choice to the student's boolean claim (statement is
    /// true). AGREE/TRUE → true, DISAGREE/FALSE → false, anything else → null
    /// (legacy client payload → UNGRADED, never a guess). Never reads a score.
    private static Boolean parseHotTakeChoice(String response) {
        if (response == null) return null;
        String r = response.trim().toUpperCase();
        if (r.equals("AGREE") || r.equals("TRUE")) return true;
        if (r.equals("DISAGREE") || r.equals("FALSE")) return false;
        return null;
    }

    /// Maps a SPOT_MISTAKE self-check to the student's "was I right?" claim.
    /// YES → true (score 1.0), NOT_QUITE → false (score 0.0), anything else
    /// (incl. null/legacy) → null → UNGRADED (never a guessed grade). This is a
    /// self-report, never a machine grade.
    private static Boolean parseSelfCheck(String v) {
        if (v == null) return null;
        String s = v.trim().toUpperCase();
        if (s.equals("YES") || s.equals("RIGHT") || s.equals("TRUE")) return true;
        if (s.equals("NOT_QUITE") || s.equals("NO") || s.equals("FALSE")) return false;
        return null;
    }

    /// Reads the persisted HOT_TAKE key (answer_json.isTrue). null if absent
    /// (legacy/blank item → UNGRADED, owned by the reaper).
    private Boolean parseHotTakeKey(String answerJson) {
        try {
            var node = objectMapper.readTree(answerJson);
            if (node.hasNonNull("isTrue")) return node.path("isTrue").asBoolean();
        } catch (Exception ignored) {
            // malformed answer_json → no key
        }
        return null;
    }

    private void updateMastery(LearningModule module, String userId) {
        // Mastery blends the trustworthy graded signals across TEST + PROVE:
        // DETERMINISTIC hot-takes (weight 1.0) and PROVE SELF_REPORTs (0.30). LEARN
        // is excluded (a completion marker, not a grade). Legacy null-signal TEST
        // rows are the OLD client-authoritative scores → EXCLUDED (never resurrect
        // the closed spoof hole); legacy null-signal PROVE rows keep full weight so
        // existing students' mastery is preserved.
        List<ModuleProgress> graded = progressRepository
                .findByModuleIdAndUserId(module.getId(), userId).stream()
                .filter(p -> ModuleStage.PROVE.name().equals(p.getStage())
                        || ModuleStage.TEST.name().equals(p.getStage()))
                .filter(p -> p.getScore() != null)
                .filter(p -> p.getSignalType() != GradingSignal.UNGRADED)
                .filter(p -> !(p.getSignalType() == null
                        && ModuleStage.TEST.name().equals(p.getStage())))
                .toList();

        // Trust-weighted mastery (Tier 3): UNGRADED contributes NOTHING (never a
        // false 0); a SELF_REPORT moves mastery gradingWeights.selfReportWeight as
        // much as a DETERMINISTIC grade — normalised by COUNT so the trust weight
        // scales the magnitude. With NO trustworthy signal, mastery is left
        // UNCHANGED (errors/skips don't move it at all).
        double weightedSum = 0;
        int gradedCount = 0;
        for (ModuleProgress p : graded) {
            double w = gradingWeights.weightFor(p.getSignalType());
            if (w <= 0) continue;
            weightedSum += w * p.getScore().doubleValue();
            gradedCount++;
        }

        if (gradedCount == 0) return; // no trustworthy signal — leave mastery unchanged

        double avg = weightedSum / gradedCount;
        // Clamp to the 0–100 contract at the WRITE so no row can ever store >100
        // (a bad weighting could otherwise persist a value that renders >100% client-side).
        double pct = Math.max(0.0, Math.min(100.0, avg * 100));
        module.setMasteryPct(BigDecimal.valueOf(pct).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * True when the module's mastery is backed ONLY by self-assessment (every graded
     * signal is SELF_REPORT, none DETERMINISTIC). Drives the parent notification's
     * "self-assessed" state instead of showing a low-trust % as if it were graded.
     */
    private boolean isSelfReportOnly(String moduleId, String userId) {
        var graded = progressRepository.findByModuleIdAndUserId(moduleId, userId).stream()
                .filter(p -> p.getSignalType() != null
                        && p.getSignalType() != GradingSignal.UNGRADED)
                .toList();
        return !graded.isEmpty()
                && graded.stream().allMatch(p -> p.getSignalType() == GradingSignal.SELF_REPORT);
    }

    /**
     * Defensive serialization clamp: masteryPct is a 0–100 contract. Clamp on the way
     * out so no legacy/miswritten row can ever render >100% (the 2600% bug class).
     */
    static BigDecimal clampPct(BigDecimal v) {
        if (v == null || v.signum() < 0) return BigDecimal.ZERO;
        return v.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(100) : v;
    }
}
