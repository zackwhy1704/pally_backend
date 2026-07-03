package com.pally.domain.module;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Facade over the learning-module lifecycle. The logic lives in three
 * single-responsibility collaborators — {@link ModuleGenerationService}
 * (build modules from wiki), {@link ModuleProgressionService}
 * (LEARN → TEST → PROVE → COMPLETE), and {@link ModuleExamReadinessService}
 * (exam-prep / class readiness). This facade keeps the existing public API so
 * controllers depend on one stable type while each responsibility stays small
 * and independently testable.
 */
@Service
@RequiredArgsConstructor
public class ModuleService {

    private final ModuleGenerationService generationService;
    private final ModuleProgressionService progressionService;
    private final ModuleExamReadinessService examReadinessService;

    // ── Generation ──────────────────────────────────────────────────────
    public List<LearningModule> generateModules(String avatarId) {
        return generationService.generateModules(avatarId);
    }

    // ── Progression ─────────────────────────────────────────────────────
    public List<Map<String, Object>> listModules(String avatarId) {
        return progressionService.listModules(avatarId);
    }

    public Map<String, Object> getModuleDetail(String moduleId, String userId) {
        return progressionService.getModuleDetail(moduleId, userId);
    }

    /** Teacher-scoped read-only preview (no answer keys). Auth is the caller's job. */
    public List<Map<String, Object>> getModulePreview(String moduleId, String classId) {
        return progressionService.getModulePreview(moduleId, classId);
    }

    public Map<String, Object> startModule(String moduleId, String userId) {
        return progressionService.startModule(moduleId, userId);
    }

    public Map<String, Object> submitAnswers(
            String moduleId, String userId, List<Map<String, String>> submissions) {
        return progressionService.submitAnswers(moduleId, userId, submissions);
    }

    public Map<String, Object> submitAnswers(
            String moduleId, String userId, List<Map<String, String>> submissions,
            int durationSeconds) {
        return progressionService.submitAnswers(moduleId, userId, submissions, durationSeconds);
    }

    public Map<String, Object> getResults(String moduleId, String userId) {
        return progressionService.getResults(moduleId, userId);
    }

    public Map<String, Object> startRevision(LearningModule module, String userId) {
        return progressionService.startRevision(module, userId);
    }

    // ── Exam readiness / evaluation ─────────────────────────────────────
    public Map<String, Object> getExamPrep(String avatarId) {
        return examReadinessService.getExamPrep(avatarId);
    }

    public Map<String, Object> getClassExamReadiness(String classId) {
        return examReadinessService.getClassExamReadiness(classId);
    }
}
