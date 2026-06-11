package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
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
import java.util.Optional;

/**
 * Orchestrates the learning module lifecycle: list, start, submit, results.
 * Enforces stage ordering: LEARN → TEST → PROVE → COMPLETE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleService {

    private final LearningModuleJpaRepository moduleRepository;
    private final ModuleContentItemJpaRepository itemRepository;
    private final ModuleProgressJpaRepository progressRepository;
    private final ModuleContentGenerator contentGenerator;
    private final ModuleProveEvaluator proveEvaluator;
    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final ObjectMapper objectMapper;

    /**
     * Generate modules for all wiki pages of an avatar. Idempotent — skips
     * pages that already have a module.
     */
    @Transactional
    public List<LearningModuleJpaEntity> generateModules(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        List<LearningModuleJpaEntity> created = new ArrayList<>();

        for (WikiPage page : pages) {
            Optional<LearningModuleJpaEntity> existing =
                    moduleRepository.findByAvatarIdAndWikiPageSlug(avatarId, page.getSlug());
            if (existing.isPresent()) {
                log.debug("[Module] Skipping existing module for slug={}", page.getSlug());
                continue;
            }

            try {
                LearningModuleJpaEntity module = contentGenerator.generate(avatar, page);
                created.add(module);
            } catch (Exception e) {
                log.error("[Module] Failed to generate module for slug={}: {}",
                        page.getSlug(), e.getMessage());
            }
        }

        log.info("[Module] Generated {} new modules for avatar={}", created.size(), avatarId);
        return created;
    }

    /**
     * List all modules for an avatar with stage, mastery, and item counts.
     */
    public List<Map<String, Object>> listModules(String avatarId) {
        List<LearningModuleJpaEntity> modules = moduleRepository.findByAvatarId(avatarId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (LearningModuleJpaEntity module : modules) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", module.getId());
            m.put("title", module.getTitle());
            m.put("wikiSlug", module.getWikiPageSlug());
            m.put("stage", module.getStage());
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
     * Get module detail with all items and progress for the current user.
     */
    public Map<String, Object> getModuleDetail(String moduleId, String userId) {
        LearningModuleJpaEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));

        List<ModuleContentItemJpaEntity> items =
                itemRepository.findByModuleIdOrderBySortOrder(moduleId);
        List<ModuleProgressJpaEntity> progress =
                progressRepository.findByModuleIdAndUserId(moduleId, userId);

        Map<String, ModuleProgressJpaEntity> progressMap = new HashMap<>();
        for (ModuleProgressJpaEntity p : progress) {
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
        for (ModuleContentItemJpaEntity item : items) {
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("id", item.getId());
            itemMap.put("stage", item.getStage());
            itemMap.put("type", item.getType());
            itemMap.put("contentJson", item.getContentJson());
            itemMap.put("sortOrder", item.getSortOrder());
            itemMap.put("tierRequired", item.getTierRequired());

            ModuleProgressJpaEntity prog = progressMap.get(item.getId());
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
        LearningModuleJpaEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));

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

        List<ModuleContentItemJpaEntity> stageItems =
                itemRepository.findByModuleIdAndStageOrderBySortOrder(
                        moduleId, currentStage.name());

        Map<String, Object> result = new HashMap<>();
        result.put("moduleId", module.getId());
        result.put("stage", currentStage.name());
        result.put("items", stageItems.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
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

        LearningModuleJpaEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));

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

            ModuleContentItemJpaEntity item = itemRepository.findById(itemId)
                    .orElseThrow(() -> new BusinessException("Item not found: " + itemId, 404));

            // Stage enforcement: only accept items for the current stage
            if (!item.getStage().equals(currentStage.name())) {
                throw new BusinessException(
                        "Item " + itemId + " belongs to stage " + item.getStage()
                                + " but current stage is " + currentStage.name(), 400);
            }

            // Record progress (upsert)
            ModuleProgressJpaEntity progress = progressRepository
                    .findByModuleIdAndUserIdAndItemId(moduleId, userId, itemId)
                    .orElseGet(() -> {
                        ModuleProgressJpaEntity p = new ModuleProgressJpaEntity();
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
                // Evaluate PROVE answers
                ModuleProveEvaluator.ProveResult evalResult =
                        proveEvaluator.evaluateAnswer(item, response);
                progress.setScore(BigDecimal.valueOf(evalResult.score()));

                // Extract targetConcept from answer_json
                try {
                    var answerNode = objectMapper.readTree(item.getAnswerJson());
                    progress.setTargetConcept(
                            answerNode.path("targetConcept").asText(null));
                } catch (Exception ignored) {
                    // non-critical
                }

                itemResult.put("score", evalResult.score());
                itemResult.put("conceptCovered", evalResult.conceptCovered());
                itemResult.put("keyPointsHit", evalResult.keyPointsHit());
                itemResult.put("keyPointsMissed", evalResult.keyPointsMissed());
                itemResult.put("feedback", evalResult.feedback());
            } else if (currentStage == ModuleStage.TEST) {
                // For TEST items, the client sends the score directly
                // (it compares against answer_json client-side for hot-takes/challenges)
                try {
                    var respNode = objectMapper.readTree(response);
                    double score = respNode.path("score").asDouble(0.0);
                    progress.setScore(BigDecimal.valueOf(score));
                    String concept = respNode.path("concept").asText(null);
                    progress.setTargetConcept(concept);
                } catch (Exception ignored) {
                    progress.setScore(BigDecimal.ZERO);
                }

                // Include the correct answer so client can show feedback
                itemResult.put("answerJson", item.getAnswerJson());
            } else {
                // LEARN: completion marker
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
            if (nextStage != null) {
                module.setStage(nextStage.name());
                if (nextStage == ModuleStage.COMPLETE) {
                    module.setCompletedAt(Instant.now());
                    // Calculate mastery from PROVE scores
                    updateMastery(module, userId);
                }
                moduleRepository.save(module);
                log.info("[Module] Advanced module={} from {} to {}",
                        moduleId, currentStage.name(), nextStage.name());
            }
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
        LearningModuleJpaEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));

        List<ModuleProgressJpaEntity> allProgress =
                progressRepository.findByModuleIdAndUserId(moduleId, userId);

        Map<String, List<ModuleProgressJpaEntity>> byStage = new HashMap<>();
        for (ModuleProgressJpaEntity p : allProgress) {
            byStage.computeIfAbsent(p.getStage(), k -> new ArrayList<>()).add(p);
        }

        // Concept mastery breakdown from PROVE results
        List<Map<String, Object>> conceptMastery = new ArrayList<>();
        List<ModuleProgressJpaEntity> proveProgress =
                byStage.getOrDefault(ModuleStage.PROVE.name(), List.of());
        for (ModuleProgressJpaEntity p : proveProgress) {
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
            List<ModuleProgressJpaEntity> stageProgress =
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
    public Map<String, Object> startRevision(LearningModuleJpaEntity module, String userId) {
        log.info("[Module] Starting revision for module={} user={}", module.getId(), userId);

        // Set back to PROVE stage for fresh questions
        module.setStage(ModuleStage.PROVE.name());
        module.setCompletedAt(null);
        moduleRepository.save(module);

        // Generate fresh PROVE items (old ones are kept — new sort_order offsets them)
        generateProveItemsAdaptively(module, userId);

        // Return the new PROVE items
        List<ModuleContentItemJpaEntity> proveItems =
                itemRepository.findByModuleIdAndStageOrderBySortOrder(
                        module.getId(), ModuleStage.PROVE.name());

        Map<String, Object> result = new HashMap<>();
        result.put("moduleId", module.getId());
        result.put("stage", ModuleStage.PROVE.name());
        result.put("revision", true);
        result.put("items", proveItems.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.getId());
            m.put("type", item.getType());
            m.put("contentJson", item.getContentJson());
            m.put("sortOrder", item.getSortOrder());
            return m;
        }).toList());

        return result;
    }

    /**
     * Aggregates per-concept mastery across all COMPLETE modules for an avatar.
     * Returns concepts sorted by mastery ascending (weakest first).
     */
    public Map<String, Object> getExamPrep(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<LearningModuleJpaEntity> modules = moduleRepository.findByAvatarId(avatarId);
        List<LearningModuleJpaEntity> complete = modules.stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .toList();

        // Gather per-concept mastery from PROVE progress across all complete modules
        List<Map<String, Object>> concepts = new ArrayList<>();
        for (LearningModuleJpaEntity mod : complete) {
            // We gather all PROVE progress for this module
            List<ModuleProgressJpaEntity> proveProgress =
                    progressRepository.findByModuleIdAndUserId(mod.getId(),
                            avatar.getUserId()).stream()
                            .filter(p -> ModuleStage.PROVE.name().equals(p.getStage()))
                            .toList();

            for (ModuleProgressJpaEntity p : proveProgress) {
                if (p.getTargetConcept() == null) continue;
                Map<String, Object> c = new HashMap<>();
                c.put("concept", p.getTargetConcept());
                c.put("mastery", p.getScore() != null ? p.getScore().doubleValue() * 100 : 0.0);
                c.put("lastAttempted", p.getCompletedAt() != null
                        ? p.getCompletedAt().toString() : null);
                c.put("moduleId", mod.getId());
                c.put("moduleTitle", mod.getTitle());
                concepts.add(c);
            }
        }

        // Sort by mastery ascending (weakest first)
        concepts.sort((a, b) -> {
            double ma = (double) a.get("mastery");
            double mb = (double) b.get("mastery");
            return Double.compare(ma, mb);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testDate", avatar.getTestDate() != null ? avatar.getTestDate().toString() : null);

        if (avatar.getTestDate() != null) {
            long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.now(), avatar.getTestDate());
            result.put("daysRemaining", daysRemaining);

            // Daily target: modules that need revision / days remaining
            long weakModules = concepts.stream()
                    .filter(c -> (double) c.get("mastery") < 60.0)
                    .count();
            int dailyTarget = daysRemaining > 0
                    ? (int) Math.max(1, Math.ceil((double) weakModules / daysRemaining))
                    : (int) weakModules;
            result.put("dailyTarget", dailyTarget);
        } else {
            result.put("daysRemaining", null);
            result.put("dailyTarget", 2);
        }

        result.put("concepts", concepts);
        result.put("recommendedOrder", concepts.stream()
                .map(c -> c.get("concept"))
                .toList());
        result.put("totalModules", modules.size());
        result.put("completedModules", complete.size());

        return result;
    }

    /**
     * Class-wide exam readiness: per-concept average mastery, students below threshold.
     */
    public Map<String, Object> getClassExamReadiness(String classId) {
        List<LearningModuleJpaEntity> classModules = moduleRepository.findByClassId(classId);
        List<LearningModuleJpaEntity> complete = classModules.stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .toList();

        // Aggregate per-concept across all students
        Map<String, List<Double>> conceptScores = new HashMap<>();
        java.util.Set<String> allStudentIds = new java.util.HashSet<>();

        for (LearningModuleJpaEntity mod : complete) {
            List<ModuleProgressJpaEntity> allProgress =
                    progressRepository.findByModuleIdAndUserId(mod.getId(), null);
            // findByModuleIdAndUserId won't work for "all users", need a different approach
            // Use module-level mastery instead
            if (mod.getMasteryPct() != null) {
                String concept = mod.getTitle(); // Use module title as concept proxy
                conceptScores.computeIfAbsent(concept, k -> new ArrayList<>())
                        .add(mod.getMasteryPct().doubleValue());
            }
        }

        // Calculate per-concept averages
        List<Map<String, Object>> conceptList = new ArrayList<>();
        int totalBelow60 = 0;
        for (var entry : conceptScores.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);
            long below = entry.getValue().stream()
                    .filter(v -> v < 60.0)
                    .count();
            totalBelow60 += (int) below;

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("concept", entry.getKey());
            c.put("avgMastery", Math.round(avg * 100.0) / 100.0);
            c.put("studentsBelowCount", (int) below);
            conceptList.add(c);
        }

        conceptList.sort((a, b) -> Double.compare(
                (double) a.get("avgMastery"), (double) b.get("avgMastery")));

        double overallAvg = conceptScores.values().stream()
                .flatMap(List::stream)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0.0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("avgReadiness", Math.round(overallAvg * 100.0) / 100.0);
        result.put("studentsBelow60Count", totalBelow60);
        result.put("totalModules", classModules.size());
        result.put("completedModules", complete.size());
        result.put("concepts", conceptList);
        result.put("suggestion", totalBelow60 > 0
                ? "Assign revision for concepts below 60%"
                : "Class is on track — consider practice quizzes for reinforcement");

        return result;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private void generateProveItemsAdaptively(LearningModuleJpaEntity module, String userId) {
        // Get TEST results to analyze weaknesses
        List<ModuleProgressJpaEntity> testProgress =
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

    private void updateMastery(LearningModuleJpaEntity module, String userId) {
        List<ModuleProgressJpaEntity> proveProgress =
                progressRepository.findByModuleIdAndUserId(module.getId(), userId)
                        .stream()
                        .filter(p -> ModuleStage.PROVE.name().equals(p.getStage()))
                        .toList();

        if (proveProgress.isEmpty()) {
            module.setMasteryPct(BigDecimal.ZERO);
            return;
        }

        double avgScore = proveProgress.stream()
                .filter(p -> p.getScore() != null)
                .mapToDouble(p -> p.getScore().doubleValue())
                .average()
                .orElse(0.0);

        module.setMasteryPct(BigDecimal.valueOf(avgScore * 100)
                .setScale(2, RoundingMode.HALF_UP));
    }
}
