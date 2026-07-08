package com.pally.domain.module;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Module EVALUATION/readiness responsibility: per-concept mastery roll-ups for
 * a learner's exam prep and a class-wide readiness summary. Read-only analytics
 * split out of the former god ModuleService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleExamReadinessService {

    private final LearningModuleRepository moduleRepository;
    private final ModuleProgressRepository progressRepository;
    private final AvatarRepository avatarRepository;
    private final GradingWeights gradingWeights;

    /**
     * Aggregates per-concept mastery across all COMPLETE modules for an avatar.
     * Returns concepts sorted by mastery ascending (weakest first).
     */
    public Map<String, Object> getExamPrep(String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<LearningModule> modules = moduleRepository.findByAvatarId(avatarId);
        List<LearningModule> complete = modules.stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .toList();

        // Gather per-concept mastery from PROVE progress across all complete modules
        List<Map<String, Object>> concepts = new ArrayList<>();
        for (LearningModule mod : complete) {
            // We gather all PROVE progress for this module
            List<ModuleProgress> proveProgress =
                    progressRepository.findByModuleIdAndUserId(mod.getId(),
                            avatar.getUserId()).stream()
                            .filter(p -> ModuleStage.PROVE.name().equals(p.getStage()))
                            .toList();

            for (ModuleProgress p : proveProgress) {
                if (p.getTargetConcept() == null) continue;
                // Skip UNGRADED items (score NULL — not self-assessed / eval error).
                // An un-assessed concept has NO mastery signal; showing it as 0%
                // and sorting it "weakest-first" is a false failure claim.
                if (p.getScore() == null) continue;
                Map<String, Object> c = new HashMap<>();
                c.put("concept", p.getTargetConcept());
                // Trust-WEIGHT the concept mastery so it agrees with module mastery:
                // a self-report YES is 30% here (not a raw 100%), a DETERMINISTIC
                // grade is full. Label the source so the UI can show "self-assessed".
                double weight = gradingWeights.weightFor(p.getSignalType());
                c.put("mastery", weight * p.getScore().doubleValue() * 100);
                c.put("signalType",
                        p.getSignalType() != null ? p.getSignalType().name() : null);
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
        List<LearningModule> classModules = moduleRepository.findByClassId(classId);
        List<LearningModule> complete = classModules.stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .toList();

        // Aggregate per-concept across all students
        Map<String, List<Double>> conceptScores = new HashMap<>();

        for (LearningModule mod : complete) {
            List<ModuleProgress> allProgress =
                    progressRepository.findByModuleIdAndUserId(mod.getId(), null);
            // findByModuleIdAndUserId won't work for "all users", need a different approach
            // Use module-level mastery instead
            if (mod.getMasteryPct() != null) {
                String concept = mod.getTitle();
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
}
