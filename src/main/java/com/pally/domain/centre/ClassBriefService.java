package com.pally.domain.centre;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generates and caches an AI-powered "what to do next" brief for centre teachers.
 *
 * <p>Flow: gather → anonymise → prompt → parse → re-identify → cache.
 * Cache is invalidated when new module_progress rows exist after the brief's generatedAt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassBriefService {

    private static final double DEFAULT_MASTERY_THRESHOLD = 60.0;
    private static final int BRIEF_MAX_TOKENS = 800;
    private static final String BRIEF_TASK = "class-brief";

    private final ClassBriefRepository briefRepository;
    private final ModuleProgressJpaRepository progressRepo;
    private final AssignmentJpaRepository assignmentRepo;
    private final UserJpaRepository userRepo;
    private final GeminiCompletionService geminiCompletion;
    private final ClaudeApiClient claudeClient;
    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns cached brief if still fresh; generates a new one otherwise.
     * "Fresh" = no module_progress row completed after generatedAt.
     */
    @Transactional
    public Map<String, Object> getOrGenerate(String classId, String moduleId) {
        Optional<ClassBrief> cached = briefRepository.findByClassIdAndModuleId(classId, moduleId);
        if (cached.isPresent()) {
            ClassBrief brief = cached.get();
            Optional<Instant> maxProgress =
                    briefRepository.findMaxProgressCompletedAt(classId, moduleId);
            boolean stale = maxProgress.isPresent()
                    && maxProgress.get().isAfter(brief.getGeneratedAt());
            if (!stale) {
                log.debug("[ClassBrief] cache hit class={} module={}", classId, moduleId);
                return parseAndReidentify(brief.getBriefJson(), classId);
            }
            log.info("[ClassBrief] cache stale — regenerating class={} module={}", classId, moduleId);
        }
        return generateAndCache(classId, moduleId);
    }

    /** Force-regenerates regardless of cache state. */
    @Transactional
    public Map<String, Object> refresh(String classId, String moduleId) {
        briefRepository.deleteByClassIdAndModuleId(classId, moduleId);
        return generateAndCache(classId, moduleId);
    }

    // ── Core pipeline ─────────────────────────────────────────────────────────

    private Map<String, Object> generateAndCache(String classId, String moduleId) {
        BriefInputs inputs = gather(classId, moduleId);
        if (inputs.studentCount() == 0) {
            throw new BusinessException("No students with progress data found for this class", 404);
        }

        String briefJson = generate(inputs);

        ClassBrief saved = briefRepository.save(ClassBrief.create(
                IdGenerator.newId(), classId, moduleId, briefJson, Instant.now()));

        log.info("[ClassBrief] generated class={} module={} concepts={} students={}",
                classId, moduleId, inputs.conceptSignals().size(), inputs.studentCount());
        return parseAndReidentify(saved.getBriefJson(), classId);
    }

    // ── Gather phase ──────────────────────────────────────────────────────────

    BriefInputs gather(String classId, String moduleId) {
        List<String> studentIds = briefRepository.findActiveStudentIds(classId);
        if (studentIds.isEmpty()) {
            return new BriefInputs(List.of(), List.of(), Map.of(), 0);
        }

        // Build anon map: userId → "Student #N" (stable alphabetical sort by displayName)
        Map<String, String> nameById = new HashMap<>();
        userRepo.findAllById(studentIds).forEach(u ->
                nameById.put(u.getId(), u.getDisplayName() != null ? u.getDisplayName() : ""));
        List<String> sortedIds = studentIds.stream()
                .sorted(Comparator.comparing(id -> nameById.getOrDefault(id, "")))
                .collect(Collectors.toList());
        Map<String, String> anonById = new LinkedHashMap<>();   // userId → "Student #N"
        Map<String, String> nameByAnon = new LinkedHashMap<>(); // "Student #N" → displayName
        for (int i = 0; i < sortedIds.size(); i++) {
            String uid = sortedIds.get(i);
            String anon = "Student #" + (i + 1);
            anonById.put(uid, anon);
            nameByAnon.put(anon, nameById.getOrDefault(uid, "Student " + (i + 1)));
        }

        // Resolve mastery threshold from assignment, default 60.0
        double threshold = resolveMasteryThreshold(classId, moduleId);

        // Fetch progress rows
        List<String> moduleIds = moduleId != null ? List.of(moduleId) : allModuleIds(classId);
        List<ModuleProgressJpaEntity> rows = moduleIds.isEmpty()
                ? List.of()
                : progressRepo.findByModuleIdInAndUserIdIn(moduleIds, studentIds);

        // Aggregate per-concept signals
        Map<String, ConceptAccumulator> accByConc = new LinkedHashMap<>();
        for (ModuleProgressJpaEntity row : rows) {
            if (row.getTargetConcept() == null || row.getScore() == null) continue;
            String anon = anonById.get(row.getUserId());
            if (anon == null) continue; // student not in active roster
            String concept = row.getTargetConcept().trim();
            double score = row.getScore().doubleValue();
            accByConc.computeIfAbsent(concept, k -> new ConceptAccumulator())
                    .record(anon, score, score >= threshold);
        }

        List<ConceptSignal> conceptSignals = accByConc.entrySet().stream()
                .map(e -> e.getValue().toSignal(e.getKey()))
                .sorted(Comparator.comparingDouble(ConceptSignal::failRate).reversed())
                .collect(Collectors.toList());

        // Per-student signals
        Map<String, StudentAccumulator> accByStudent = new LinkedHashMap<>();
        for (ModuleProgressJpaEntity row : rows) {
            if (row.getScore() == null) continue;
            String anon = anonById.get(row.getUserId());
            if (anon == null) continue;
            String concept = row.getTargetConcept() != null ? row.getTargetConcept().trim() : null;
            double score = row.getScore().doubleValue();
            accByStudent.computeIfAbsent(anon, k -> new StudentAccumulator())
                    .record(concept, score, score >= threshold);
        }
        List<StudentSignal> studentSignals = accByStudent.entrySet().stream()
                .map(e -> e.getValue().toSignal(e.getKey()))
                .sorted(Comparator.comparingDouble(StudentSignal::passRate))
                .collect(Collectors.toList());

        return new BriefInputs(conceptSignals, studentSignals, nameByAnon, sortedIds.size());
    }

    private double resolveMasteryThreshold(String classId, String moduleId) {
        List<AssignmentJpaEntity> assignments = assignmentRepo.findByClassId(classId);
        for (AssignmentJpaEntity a : assignments) {
            if (a.getMasteryThreshold() == null) continue;
            if (moduleId == null) return a.getMasteryThreshold().doubleValue();
            // Check if this assignment covers the requested module
            String modIds = a.getModuleIds();
            if (modIds != null && modIds.contains(moduleId)) {
                return a.getMasteryThreshold().doubleValue();
            }
        }
        return DEFAULT_MASTERY_THRESHOLD;
    }

    private List<String> allModuleIds(String classId) {
        List<AssignmentJpaEntity> assignments = assignmentRepo.findByClassId(classId);
        List<String> ids = new ArrayList<>();
        for (AssignmentJpaEntity a : assignments) {
            if (a.getModuleIds() == null || a.getModuleIds().isBlank()) continue;
            try {
                JsonNode arr = objectMapper.readTree(a.getModuleIds());
                if (arr.isArray()) {
                    arr.forEach(n -> { if (!n.asText().isBlank()) ids.add(n.asText()); });
                }
            } catch (JsonProcessingException ignored) {
                // module_ids is comma-separated in some older rows
                for (String id : a.getModuleIds().split(",")) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty()) ids.add(trimmed);
                }
            }
        }
        return ids.stream().distinct().collect(Collectors.toList());
    }

    // ── Generate phase ────────────────────────────────────────────────────────

    String generate(BriefInputs inputs) {
        String prompt = buildPrompt(inputs);
        String raw = geminiCompletion.complete(BRIEF_MAX_TOKENS, prompt, BRIEF_TASK);

        // Validate JSON — retry with Haiku on parse failure
        try {
            objectMapper.readTree(raw);
            return extractJson(raw);
        } catch (Exception e) {
            log.warn("[ClassBrief] Gemini response not valid JSON — retrying with Haiku");
            String retry = claudeClient.complete(
                    modelRouter.getHaikuModel(), BRIEF_MAX_TOKENS, prompt, BRIEF_TASK);
            try {
                objectMapper.readTree(retry);
                return extractJson(retry);
            } catch (Exception e2) {
                throw new BusinessException(
                        "AI service returned unparseable brief — please try again", 503);
            }
        }
    }

    private String buildPrompt(BriefInputs inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an instructional coach briefing a teacher before their next class.
                Return ONLY a JSON object — no prose, no markdown fences.

                JSON shape (required):
                {
                  "openWith": "<one concept or activity to open the lesson>",
                  "focusConcepts": [
                    {"name":"<concept>","failRate":<0-1>,"failingStudents":["Student #N",...]},
                    ...
                  ],
                  "checkOn": ["Student #N", ...],
                  "suggestedGroups": [["Student #N","Student #M"], ...],
                  "skipLine": "<optional: note if all students have mastered the material>"
                }

                Rules:
                - focusConcepts: list concepts where failRate > 0.3, sorted worst first, max 5.
                - checkOn: students with overall pass rate < 50%, max 5.
                - suggestedGroups: pair a strong student with a struggling one per weak concept, max 3 groups.
                - skipLine: include only when ALL students passed ALL concepts.
                - Use the exact anonymised IDs provided — never invent new ones.

                DATA:
                """);

        if (inputs.conceptSignals().isEmpty()) {
            sb.append("No concept data available yet — tell the teacher to check back after students complete their first module.\n");
        } else {
            sb.append("Concept signals (worst first):\n");
            for (ConceptSignal cs : inputs.conceptSignals()) {
                sb.append(String.format("  - %s: failRate=%.2f failingStudents=%s%n",
                        cs.concept(), cs.failRate(), cs.failingStudents()));
            }
        }
        if (!inputs.studentSignals().isEmpty()) {
            sb.append("\nStudent signals (weakest first):\n");
            for (StudentSignal ss : inputs.studentSignals()) {
                sb.append(String.format("  - %s: passRate=%.2f weakConcepts=%s%n",
                        ss.anonId(), ss.passRate(), ss.weakConcepts()));
            }
        }
        return sb.toString();
    }

    /** Extracts the first {...} JSON block from a possibly prose-wrapped response. */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    // ── Re-identify phase ─────────────────────────────────────────────────────

    /**
     * The brief JSON contains "Student #N" anon IDs. This method replaces them
     * with real display names by looking up the current anon map from active students.
     */
    private Map<String, Object> parseAndReidentify(String briefJson, String classId) {
        Map<String, String> nameByAnon = buildAnonMapForClass(classId);
        try {
            String replaced = replaceAnonIds(briefJson, nameByAnon);
            //noinspection unchecked
            return objectMapper.readValue(replaced, Map.class);
        } catch (Exception e) {
            log.warn("[ClassBrief] brief JSON malformed for class={}: {}", classId, e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("openWith", "Review previous material with students.");
            fallback.put("focusConcepts", List.of());
            fallback.put("checkOn", List.of());
            fallback.put("suggestedGroups", List.of());
            fallback.put("skipLine", null);
            return fallback;
        }
    }

    private Map<String, String> buildAnonMapForClass(String classId) {
        List<String> studentIds = briefRepository.findActiveStudentIds(classId);
        Map<String, String> nameById = new HashMap<>();
        userRepo.findAllById(studentIds).forEach(u ->
                nameById.put(u.getId(), u.getDisplayName() != null ? u.getDisplayName() : ""));
        List<String> sortedIds = studentIds.stream()
                .sorted(Comparator.comparing(id -> nameById.getOrDefault(id, "")))
                .collect(Collectors.toList());
        Map<String, String> nameByAnon = new LinkedHashMap<>();
        for (int i = 0; i < sortedIds.size(); i++) {
            nameByAnon.put("Student #" + (i + 1),
                    nameById.getOrDefault(sortedIds.get(i), "Student " + (i + 1)));
        }
        return nameByAnon;
    }

    private String replaceAnonIds(String json, Map<String, String> nameByAnon) {
        // Replace longest keys first to avoid "Student #1" matching inside "Student #10"
        List<String> keys = new ArrayList<>(nameByAnon.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String anon : keys) {
            String real = nameByAnon.get(anon);
            json = json.replace("\"" + anon + "\"", "\"" + escapeJson(real) + "\"");
        }
        return json;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Internal accumulators ─────────────────────────────────────────────────

    private static class ConceptAccumulator {
        int total = 0;
        int failed = 0;
        List<String> failingStudents = new ArrayList<>();

        void record(String anonId, double score, boolean passed) {
            total++;
            if (!passed) {
                failed++;
                if (!failingStudents.contains(anonId)) failingStudents.add(anonId);
            }
        }

        ConceptSignal toSignal(String concept) {
            double failRate = total > 0 ? (double) failed / total : 0.0;
            return new ConceptSignal(concept, failRate, List.copyOf(failingStudents));
        }
    }

    private static class StudentAccumulator {
        int total = 0;
        int passed = 0;
        List<String> weakConcepts = new ArrayList<>();

        void record(String concept, double score, boolean pass) {
            total++;
            if (pass) passed++;
            else if (concept != null && !weakConcepts.contains(concept)) weakConcepts.add(concept);
        }

        StudentSignal toSignal(String anonId) {
            double passRate = total > 0 ? (double) passed / total : 0.0;
            return new StudentSignal(anonId, passRate, List.copyOf(weakConcepts));
        }
    }

    // ── Value types ───────────────────────────────────────────────────────────

    record ConceptSignal(String concept, double failRate, List<String> failingStudents) {}

    record StudentSignal(String anonId, double passRate, List<String> weakConcepts) {}

    record BriefInputs(
            List<ConceptSignal> conceptSignals,
            List<StudentSignal> studentSignals,
            Map<String, String> nameByAnon,
            int studentCount) {}
}
