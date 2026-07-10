package com.pally.domain.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.group.ClassGroupService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anonymous "muddiest point" voting: each student picks the one concept in a
 * module they found trickiest. Votes are deduplicated per (module, student) and
 * the aggregate exposes counts only — never user identities. When ≥30% of a
 * module's completers converge on one concept, a heads-up is posted to the
 * class's CLASS group.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MuddiestService {

    /** Threshold of completers voting one concept that triggers a class post. */
    private static final double POST_THRESHOLD = 0.30;
    /** Stage that marks a student as having "completed" the module. */
    private static final String COMPLETER_STAGE = "PROVE";

    private final MuddiestVoteRepository voteRepo;
    private final LearningModuleRepository moduleRepo;
    private final ModuleContentItemRepository itemRepo;
    private final ModuleProgressRepository progressRepo;
    private final ClassGroupService classGroupService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Records (or changes) a student's muddiest-point vote for a module.
     * {@code conceptId} must be one of the module's concepts. UPSERT: re-voting
     * replaces the previously chosen concept.
     */
    @Transactional
    public Map<String, Object> vote(String moduleId, String userId, String conceptId) {
        LearningModule module = moduleRepo.findById(moduleId)
                .orElseThrow(() -> new BusinessException("Module not found", 404));
        if (conceptId == null || conceptId.isBlank()) {
            throw new BusinessException("conceptId is required", 400);
        }
        Set<String> concepts = conceptIdsForModule(moduleId);
        if (!concepts.isEmpty() && !concepts.contains(conceptId)) {
            throw new BusinessException(
                    "conceptId is not one of this module's concepts", 400);
        }

        MuddiestVote vote = voteRepo.findByModuleIdAndUserId(moduleId, userId)
                .orElseGet(() -> {
                    MuddiestVote v = new MuddiestVote();
                    v.setId(IdGenerator.newId());
                    v.setModuleId(moduleId);
                    v.setUserId(userId);
                    v.setCreatedAt(Instant.now());
                    return v;
                });
        vote.setConceptId(conceptId);
        vote.setClassId(module.getClassId());
        voteRepo.save(vote);
        log.info("[Muddiest] vote module={} concept={} (user dedup only)", moduleId, conceptId);

        maybePostThreshold(module);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("moduleId", moduleId);
        resp.put("conceptId", conceptId);
        resp.put("recorded", true);
        return resp;
    }

    /**
     * Class-level aggregate for a module: a list of {conceptId, conceptLabel,
     * count}. Contains NO user identifiers.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> aggregate(String moduleId) {
        Map<String, String> labels = conceptLabels(moduleId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : voteRepo.countByConcept(moduleId)) {
            String conceptId = (String) row[0];
            long count = ((Number) row[1]).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conceptId", conceptId);
            m.put("conceptLabel", labels.getOrDefault(conceptId, conceptId));
            m.put("count", count);
            out.add(m);
        }
        return out;
    }

    // ── Threshold post ───────────────────────────────────────────────────

    private void maybePostThreshold(LearningModule module) {
        String classId = module.getClassId();
        if (classId == null || classId.isBlank()) return; // personal module, no class group

        long completers = progressRepo
                .countDistinctCompletersByStage(module.getId(), COMPLETER_STAGE);
        if (completers <= 0) return;

        // Find the leading concept and its count.
        List<Object[]> counts = voteRepo.countByConcept(module.getId());
        if (counts.isEmpty()) return;
        Object[] top = counts.get(0); // ordered desc by count
        String conceptId = (String) top[0];
        long count = ((Number) top[1]).longValue();

        if ((double) count / (double) completers >= POST_THRESHOLD) {
            String label = conceptLabels(module.getId()).getOrDefault(conceptId, conceptId);
            String body = count + " of " + completers + " found “" + label
                    + "” the trickiest — we’ll go over it in class.";
            classGroupService.postSystemMessage(
                    classId, ClassGroupService.KIND_MUDDIEST, body, module.getId());
        }
    }

    // ── Concept extraction ───────────────────────────────────────────────

    /** Distinct concept ids declared anywhere in the module's content items. */
    private Set<String> conceptIdsForModule(String moduleId) {
        return conceptLabels(moduleId).keySet();
    }

    /**
     * Maps conceptId → human label by scanning the module's content items. We
     * read {@code answer_json.targetConcept} and {@code content_json.concept}
     * (the two places concepts are stored across stages).
     *
     * <p>DELIBERATELY reads the FULL set (findByModuleId*, NOT findServable*). This
     * extracts concept LABELS, not item content — nothing here reaches a student as a
     * card/question, so the servable filter doesn't apply. More importantly it must
     * RESOLVE the label for any concept a student ALREADY voted on — including one
     * whose item later went DRAFT/ARCHIVED — and a servable-only scan would surface a
     * raw conceptId instead of its label in the aggregate + class post. Do NOT "tidy"
     * this onto findServable*. (See ModuleContentItemRepository.SERVABLE_STATUSES.)
     */
    private Map<String, String> conceptLabels(String moduleId) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (ModuleContentItem item : itemRepo.findByModuleIdOrderBySortOrder(moduleId)) {
            extractConcept(item.getAnswerJson(), labels);
            extractConcept(item.getContentJson(), labels);
        }
        return labels;
    }

    private void extractConcept(String json, Map<String, String> into) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(json);
            for (String key : new String[]{"targetConcept", "concept"}) {
                JsonNode c = node.get(key);
                if (c != null && c.isTextual() && !c.asText().isBlank()) {
                    String v = c.asText();
                    into.putIfAbsent(v, v);
                }
            }
        } catch (Exception e) {
            // Non-JSON or unexpected shape — skip silently; not all items
            // declare a concept.
            log.trace("[Muddiest] could not parse concept from item json: {}", e.getMessage());
        }
    }

    /** Unmodifiable snapshot of concept labels for a module (used in tests/clients). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listConcepts(String moduleId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : conceptLabels(moduleId).entrySet()) {
            out.add(new LinkedHashMap<>(Map.of(
                    "conceptId", e.getKey(), "conceptLabel", e.getValue())));
        }
        // Defensive: keep deterministic order via insertion-ordered set.
        Set<String> seen = new LinkedHashSet<>();
        out.removeIf(m -> !seen.add((String) m.get("conceptId")));
        return out;
    }
}
