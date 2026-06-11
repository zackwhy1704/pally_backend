package com.pally.domain.module;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.WikiPage;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates all content items for a learning module from a wiki page using an LLM.
 * LEARN and TEST items are generated upfront; PROVE items are generated on-demand
 * based on TEST results (adaptive).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleContentGenerator {

    private static final int MAX_TOKENS = 1500;

    private final GeminiCompletionService geminiCompletion;
    private final ObjectMapper objectMapper;
    private final LearningModuleJpaRepository moduleRepository;
    private final ModuleContentItemJpaRepository itemRepository;

    /**
     * Generates a learning module with LEARN and TEST items for a wiki page.
     * PROVE items are NOT generated here — they are generated on-demand in
     * {@link #generateProveQuestions}.
     *
     * @return the saved module entity
     */
    @Transactional
    public LearningModuleJpaEntity generate(Avatar avatar, WikiPage page) {
        String tier = avatar.isCentreAvatar() ? "CENTRE" : "FREE";
        String level = avatar.getGradeLevel() != null ? avatar.getGradeLevel() : "primary school";
        String subject = avatar.getSubject().label();

        // Create module entity
        LearningModuleJpaEntity module = new LearningModuleJpaEntity();
        module.setId(IdGenerator.newId());
        module.setAvatarId(avatar.getId());
        module.setClassId(avatar.getClassId());
        module.setWikiPageSlug(page.getSlug());
        module.setTitle(page.getTitle());
        module.setStage(ModuleStage.LEARN.name());
        module.setTier(tier);
        module.setMasteryPct(BigDecimal.ZERO);
        module.setCreatedAt(Instant.now());
        module = moduleRepository.save(module);

        String content = truncate(page.getContent(), 3000);
        List<ModuleContentItemJpaEntity> allItems = new ArrayList<>();

        // Generate LEARN items (micro-cards)
        allItems.addAll(generateMicroCards(module.getId(), content, level, subject, tier));

        // Generate TEST items
        allItems.addAll(generateHotTakes(module.getId(), content, level, subject, tier));
        allItems.addAll(generateSpotMistake(module.getId(), content, level, subject));
        allItems.addAll(generateChallenges(module.getId(), content, level, subject, tier));

        itemRepository.saveAll(allItems);
        log.info("[Module] Generated module id={} slug={} items={} tier={}",
                module.getId(), page.getSlug(), allItems.size(), tier);

        return module;
    }

    /**
     * Generates adaptive PROVE questions based on TEST results.
     * Targets concepts the student scored poorly on.
     */
    @Transactional
    public List<ModuleContentItemJpaEntity> generateProveQuestions(
            LearningModuleJpaEntity module,
            WikiPage page,
            List<ModuleProgressJpaEntity> testResults,
            String tier) {

        String level = "primary school"; // fallback; caller can improve
        int n = "CENTRE".equals(tier) ? 5 : 3;

        String testSummary = testResults.stream()
                .map(r -> {
                    String concept = r.getTargetConcept() != null ? r.getTargetConcept() : "unknown";
                    String score = r.getScore() != null ? r.getScore().toPlainString() : "0";
                    return concept + ": " + score;
                })
                .collect(Collectors.joining("\n"));

        String prompt = """
                A student studied %s and scored these on their test:
                %s

                Generate %d prove-it questions. Each targets ONE specific concept.
                The student must answer in 1-3 sentences to demonstrate understanding.
                Prioritize concepts the student scored poorly on.

                Reply ONLY with a JSON array:
                [{"question":"...","targetConcept":"...","expectedKeyPoints":["..."],"difficulty":"easy/medium/hard"}]
                """.formatted(page.getTitle(), testSummary, n);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "module-prove-gen");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<>() {});

            List<ModuleContentItemJpaEntity> items = new ArrayList<>();
            int existingCount = itemRepository.countByModuleIdAndStage(
                    module.getId(), ModuleStage.PROVE.name());

            for (int i = 0; i < parsed.size(); i++) {
                Map<String, Object> q = parsed.get(i);
                ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
                item.setId(IdGenerator.newId());
                item.setModuleId(module.getId());
                item.setStage(ModuleStage.PROVE.name());
                item.setType(ContentItemType.PROVE_QUESTION.name());
                item.setContentJson(objectMapper.writeValueAsString(q));

                // Store expectedKeyPoints in answer_json for evaluation
                @SuppressWarnings("unchecked")
                List<String> keyPoints = (List<String>) q.getOrDefault("expectedKeyPoints", List.of());
                String target = (String) q.getOrDefault("targetConcept", "");
                item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                        "expectedKeyPoints", keyPoints,
                        "targetConcept", target)));

                item.setSortOrder(existingCount + i);
                item.setTierRequired(tier);
                item.setCreatedAt(Instant.now());
                items.add(item);
            }

            itemRepository.saveAll(items);
            log.info("[Module] Generated {} PROVE questions for module={}",
                    items.size(), module.getId());
            return items;

        } catch (Exception e) {
            log.error("[Module] Failed to generate PROVE questions for module={}",
                    module.getId(), e);
            return List.of();
        }
    }

    // ── LEARN: micro-cards ───────────────────────────────────────────────

    private List<ModuleContentItemJpaEntity> generateMicroCards(
            String moduleId, String content, String level, String subject, String tier) {
        int n = "CENTRE".equals(tier) ? 6 : 4;

        String prompt = """
                Split this educational content into %d bite-size concept cards for a %s student studying %s.
                Each card covers ONE concept, under 60 words, with key terms in bold.
                Include a narration_hint field (how you'd explain this conversationally — for TTS narration).

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"title":"...","body":"...","keyTerms":["..."],"narration_hint":"..."}]
                """.formatted(n, level, subject, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "module-learn-gen");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<>() {});

            List<ModuleContentItemJpaEntity> items = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
                item.setId(IdGenerator.newId());
                item.setModuleId(moduleId);
                item.setStage(ModuleStage.LEARN.name());
                item.setType(ContentItemType.MICRO_CARD.name());
                item.setContentJson(objectMapper.writeValueAsString(parsed.get(i)));
                item.setSortOrder(i);
                item.setTierRequired("FREE");
                item.setCreatedAt(Instant.now());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.error("[Module] Failed to generate micro-cards for module={}",
                    moduleId, e);
            return List.of();
        }
    }

    // ── TEST: hot takes ──────────────────────────────────────────────────

    private List<ModuleContentItemJpaEntity> generateHotTakes(
            String moduleId, String content, String level, String subject, String tier) {
        int n = "CENTRE".equals(tier) ? 3 : 2;

        String prompt = """
                Generate %d true/false statements about this content for a %s student.
                At least one must be a common misconception (false).

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"statement":"...","isTrue":true,"explanation":"..."}]
                """.formatted(n, level, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "module-hottake-gen");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<>() {});

            List<ModuleContentItemJpaEntity> items = new ArrayList<>();
            int offset = 100; // hot takes start at sort_order 100
            for (int i = 0; i < parsed.size(); i++) {
                ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
                item.setId(IdGenerator.newId());
                item.setModuleId(moduleId);
                item.setStage(ModuleStage.TEST.name());
                item.setType(ContentItemType.HOT_TAKE.name());
                item.setContentJson(objectMapper.writeValueAsString(
                        Map.of("statement", parsed.get(i).getOrDefault("statement", ""))));
                item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                        "isTrue", parsed.get(i).getOrDefault("isTrue", true),
                        "explanation", parsed.get(i).getOrDefault("explanation", ""))));
                item.setSortOrder(offset + i);
                item.setTierRequired("FREE");
                item.setCreatedAt(Instant.now());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.error("[Module] Failed to generate hot takes for module={}",
                    moduleId, e);
            return List.of();
        }
    }

    // ── TEST: spot the mistake ───────────────────────────────────────────

    private List<ModuleContentItemJpaEntity> generateSpotMistake(
            String moduleId, String content, String level, String subject) {

        String prompt = """
                Write ONE plausible but WRONG worked solution for a problem from this content.
                Introduce a common %s-student misconception. The student must find the error.

                Content:
                %s

                Reply ONLY with JSON:
                {"problem":"...","wrongSolution":"...","errorDescription":"...","correctSolution":"..."}
                """.formatted(level, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "module-spotmistake-gen");
            String json = extractJson(raw, '{', '}');
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<>() {});

            ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
            item.setId(IdGenerator.newId());
            item.setModuleId(moduleId);
            item.setStage(ModuleStage.TEST.name());
            item.setType(ContentItemType.SPOT_MISTAKE.name());
            item.setContentJson(objectMapper.writeValueAsString(Map.of(
                    "problem", parsed.getOrDefault("problem", ""),
                    "wrongSolution", parsed.getOrDefault("wrongSolution", ""))));
            item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                    "errorDescription", parsed.getOrDefault("errorDescription", ""),
                    "correctSolution", parsed.getOrDefault("correctSolution", ""))));
            item.setSortOrder(200); // spot-mistake at sort_order 200
            item.setTierRequired("FREE");
            item.setCreatedAt(Instant.now());
            return List.of(item);
        } catch (Exception e) {
            log.error("[Module] Failed to generate spot-mistake for module={}",
                    moduleId, e);
            return List.of();
        }
    }

    // ── TEST: challenges ─────────────────────────────────────────────────

    private List<ModuleContentItemJpaEntity> generateChallenges(
            String moduleId, String content, String level, String subject, String tier) {
        int n = "CENTRE".equals(tier) ? 3 : 1;

        String prompt = """
                Generate %d application questions that test whether a %s student can USE these concepts.
                Include word problems where possible.

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"question":"...","answer":"...","explanation":"...","difficulty":"easy"}]
                """.formatted(n, level, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "module-challenge-gen");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<>() {});

            List<ModuleContentItemJpaEntity> items = new ArrayList<>();
            int offset = 300; // challenges start at sort_order 300
            for (int i = 0; i < parsed.size(); i++) {
                ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
                item.setId(IdGenerator.newId());
                item.setModuleId(moduleId);
                item.setStage(ModuleStage.TEST.name());
                item.setType(ContentItemType.CHALLENGE.name());
                item.setContentJson(objectMapper.writeValueAsString(
                        Map.of("question", parsed.get(i).getOrDefault("question", ""),
                                "difficulty", parsed.get(i).getOrDefault("difficulty", "easy"))));
                item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                        "answer", parsed.get(i).getOrDefault("answer", ""),
                        "explanation", parsed.get(i).getOrDefault("explanation", ""))));
                item.setSortOrder(offset + i);
                item.setTierRequired("FREE");
                item.setCreatedAt(Instant.now());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.error("[Module] Failed to generate challenges for module={}",
                    moduleId, e);
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    String extractJson(String raw, char openChar, char closeChar) {
        if (raw == null || raw.isBlank()) return openChar == '[' ? "[]" : "{}";
        String trimmed = raw.strip();
        // Strip markdown fences
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
        }
        int start = trimmed.indexOf(openChar);
        int end = trimmed.lastIndexOf(closeChar);
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return openChar == '[' ? "[]" : "{}";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
