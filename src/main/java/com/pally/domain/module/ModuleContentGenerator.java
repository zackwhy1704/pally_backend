package com.pally.domain.module;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.groundedness.GroundednessVerifier;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    // Micro-cards are the largest payload (up to 6 cards × title+body+keyTerms)
    // and were truncating at MAX_TOKENS — dropping the whole LEARN batch (B2).
    // Give them real headroom; lenient parse + one retry handle the rest.
    private static final int MICRO_CARD_TOKENS = 3000;

    private final GeminiCompletionService geminiCompletion;
    private final ObjectMapper objectMapper;
    private final PremiumService premiumService;
    private final GroundednessVerifier groundednessVerifier;
    private final ModuleWriter moduleWriter;

    /**
     * Generates a learning module with LEARN and TEST items for a wiki page.
     * PROVE items are NOT generated here — they are generated on-demand in
     * {@link #generateProveQuestions}.
     *
     * @return the saved module domain object
     */
    // A2: NOT @Transactional — the four Gemini calls below must not pin a DB
    // connection. The module's UUID is assigned in-memory so items can reference it
    // without a DB round-trip; only the final persist runs in a (brief) transaction.
    public LearningModule generate(Avatar avatar, WikiPage page) {
        String tier = resolveContentTier(avatar);
        String level = avatar.getGradeLevel() != null ? avatar.getGradeLevel() : "primary school";
        String subject = avatar.getSubject().label();

        // Build the module in memory (UUID assigned now — no DB write yet).
        LearningModule module = new LearningModule();
        module.setId(IdGenerator.newId());
        module.setAvatarId(avatar.getId());
        module.setClassId(avatar.getClassId());
        module.setWikiPageSlug(page.getSlug());
        module.setTitle(page.getTitle());
        module.setStage(ModuleStage.LEARN.name());
        module.setTier(tier);
        module.setMasteryPct(BigDecimal.ZERO);
        module.setCreatedAt(Instant.now());

        String content = truncate(page.getContent(), 3000);
        List<ModuleContentItem> allItems = new ArrayList<>();

        // ── AI work — NO transaction held across these ──────────────────────
        allItems.addAll(generateMicroCards(module.getId(), content, level, subject, tier)); // LEARN
        allItems.addAll(generateHotTakes(module.getId(), content, level, subject, tier));   // TEST
        allItems.addAll(generateSpotMistake(module.getId(), content, level, subject));
        allItems.addAll(generateChallenges(module.getId(), content, level, subject, tier));
        tagGroundedness(page, allItems);

        // ── Short transactional persist ─────────────────────────────────────
        LearningModule saved = moduleWriter.saveModuleWithItems(module, allItems);
        log.info("[Module] Generated module id={} slug={} items={} tier={}",
                saved.getId(), page.getSlug(), allItems.size(), tier);
        return saved;
    }

    /**
     * Re-generates content items for an existing module with optional teacher guidance.
     * Deletes all existing items first, then generates fresh LEARN + TEST items as DRAFT
     * so the centre can review before they go live. PROVE items are omitted (adaptive,
     * generated on-demand from student results as before).
     *
     * @param guidance optional free-text feedback from the teacher; prepended to each
     *                 generation prompt so the model respects it
     */
    // A2: NOT @Transactional — the four draft Gemini calls run with no open
    // transaction. The delete + re-insert happen together in the short write at the end.
    public void regenerateAsDraft(Avatar avatar, WikiPage page, LearningModule module, String guidance) {
        String tier = resolveContentTier(avatar);
        String level = avatar.getGradeLevel() != null ? avatar.getGradeLevel() : "primary school";
        String subject = avatar.getSubject().label();
        String content = truncate(page.getContent(), 3000);

        String guidanceSection = (guidance != null && !guidance.isBlank())
                ? "\n\nTeacher guidance to incorporate:\n" + guidance.strip() + "\n"
                : "";

        // ── AI work — NO transaction held across these ──────────────────────
        List<ModuleContentItem> allItems = new ArrayList<>();
        allItems.addAll(generateMicroCardsDraft(module.getId(), content, level, subject, tier, guidanceSection));
        allItems.addAll(generateHotTakesDraft(module.getId(), content, level, subject, tier, guidanceSection));
        allItems.addAll(generateSpotMistakeDraft(module.getId(), content, level, subject, guidanceSection));
        allItems.addAll(generateChallengesDraft(module.getId(), content, level, subject, tier, guidanceSection));
        tagGroundedness(page, allItems);

        // ── Short transactional replace (delete old + insert new, atomic) ───
        moduleWriter.replaceItems(module.getId(), allItems);
        log.info("[CentreRegen] Regenerated module={} slug={} items={} withGuidance={}",
                module.getId(), page.getSlug(), allItems.size(), guidance != null && !guidance.isBlank());
    }

    // ── Groundedness gate (B3) ───────────────────────────────────────────────

    /**
     * Runs the groundedness gate over each generated item against the source page,
     * attaching a flag payload to items that assert a hard fact not entailed by the
     * notes (or that contradict them). Fail-open: any error skips tagging, never
     * blocks generation. NOTE: auto-regenerate-on-CONTRADICTED is deferred — we FLAG
     * contradictions terminally (with the source line) so the teacher adjudicates,
     * rather than add a retry loop to the generation hot path.
     */
    private void tagGroundedness(WikiPage page, List<ModuleContentItem> items) {
        String source = page.getContent();
        if (source == null || source.isBlank()) return;
        boolean sourceVerified = page.isHumanVerified();
        for (ModuleContentItem item : items) {
            try {
                List<String> texts = extractItemTexts(item);
                if (texts.isEmpty()) continue;
                GroundednessVerifier.Report report = groundednessVerifier.check(source, texts);
                if (report.needsAttention()) {
                    item.setVerificationJson(buildVerificationPayload(report, sourceVerified));
                }
            } catch (Exception e) {
                log.warn("[Groundedness] check failed for item={} — skipping (fail open): {}",
                        item.getId(), e.getMessage());
            }
        }
    }

    /** Collects the human-readable text leaves from an item's content + answer JSON. */
    private List<String> extractItemTexts(ModuleContentItem item) {
        List<String> out = new ArrayList<>();
        collectStrings(item.getContentJson(), out);
        collectStrings(item.getAnswerJson(), out);
        return out;
    }

    private void collectStrings(String json, List<String> out) {
        if (json == null || json.isBlank()) return;
        try {
            walk(objectMapper.readTree(json), out);
        } catch (Exception ignored) {
            // Not JSON — treat the whole blob as text.
            if (json.length() > 12) out.add(json);
        }
    }

    private void walk(JsonNode node, List<String> out) {
        if (node.isTextual()) {
            String t = node.asText();
            if (t.length() > 12) out.add(t); // skip keys / short labels
        } else if (node.isArray() || node.isObject()) {
            node.forEach(child -> walk(child, out));
        }
    }

    private String buildVerificationPayload(GroundednessVerifier.Report report, boolean sourceVerified) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", "flagged");
        payload.put("gate", "groundedness");
        // Phase 5: tell the teacher whether the source line being quoted is itself
        // teacher-verified, so they know how much to trust it.
        payload.put("sourcePageVerified", sourceVerified);
        List<Map<String, Object>> flags = new ArrayList<>();
        for (GroundednessVerifier.ClaimVerdict v : report.verdicts()) {
            if (v.action() == GroundednessVerifier.Action.FLAG
                    || v.action() == GroundednessVerifier.Action.CONTRADICTED) {
                Map<String, Object> f = new java.util.LinkedHashMap<>();
                f.put("claim", v.claim());
                f.put("reason", v.action() == GroundednessVerifier.Action.CONTRADICTED
                        ? "contradicts your uploaded notes"
                        : "not found in your uploaded notes");
                f.put("sourceQuote", v.sourceQuote());
                flags.add(f);
            }
        }
        payload.put("flags", flags);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private List<ModuleContentItem> generateMicroCardsDraft(
            String moduleId, String content, String level, String subject, String tier, String guidanceSection) {
        int n = "CENTRE".equals(tier) ? 6 : 4;
        String prompt = """
                Split this educational content into %d bite-size concept cards for a %s student studying %s.
                Each card covers ONE concept, under 60 words, with key terms in bold.%s

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"title":"...","body":"...","keyTerms":["..."]}]
                """.formatted(n, level, subject, guidanceSection, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "centre-regen-learn");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<ModuleContentItem> items = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                items.add(buildDraftItem(moduleId, ModuleStage.LEARN.name(), ContentItemType.MICRO_CARD.name(),
                        objectMapper.writeValueAsString(parsed.get(i)), null, i));
            }
            return items;
        } catch (Exception e) {
            log.error("[CentreRegen] micro-cards failed moduleId={}: {}", moduleId, e.getMessage());
            return List.of();
        }
    }

    private List<ModuleContentItem> generateHotTakesDraft(
            String moduleId, String content, String level, String subject, String tier, String guidanceSection) {
        int n = "CENTRE".equals(tier) ? 3 : 2;
        String prompt = """
                Generate %d true/false statements about this content for a %s student.
                At least one must be a common misconception (false).%s

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"statement":"...","isTrue":true,"explanation":"..."}]
                """.formatted(n, level, guidanceSection, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "centre-regen-hottake");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<ModuleContentItem> items = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                String contentJson = objectMapper.writeValueAsString(
                        Map.of("statement", parsed.get(i).getOrDefault("statement", "")));
                String answerJson = objectMapper.writeValueAsString(Map.of(
                        "isTrue", parsed.get(i).getOrDefault("isTrue", true),
                        "explanation", parsed.get(i).getOrDefault("explanation", "")));
                items.add(buildDraftItem(moduleId, ModuleStage.TEST.name(), ContentItemType.HOT_TAKE.name(),
                        contentJson, answerJson, 100 + i));
            }
            return items;
        } catch (Exception e) {
            log.error("[CentreRegen] hot-takes failed moduleId={}: {}", moduleId, e.getMessage());
            return List.of();
        }
    }

    private List<ModuleContentItem> generateSpotMistakeDraft(
            String moduleId, String content, String level, String subject, String guidanceSection) {
        String prompt = """
                Write ONE plausible but WRONG worked solution for a problem from this content.
                Introduce a common %s-student misconception. The student must find the error.%s

                Content:
                %s

                Reply ONLY with JSON:
                {"problem":"...","wrongSolution":"...","errorDescription":"...","correctSolution":"..."}
                """.formatted(level, guidanceSection, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "centre-regen-spotmistake");
            String json = extractJson(raw, '{', '}');
            Map<String, Object> parsed = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String contentJson = objectMapper.writeValueAsString(Map.of(
                    "problem", parsed.getOrDefault("problem", ""),
                    "wrongSolution", parsed.getOrDefault("wrongSolution", "")));
            String answerJson = objectMapper.writeValueAsString(Map.of(
                    "errorDescription", parsed.getOrDefault("errorDescription", ""),
                    "correctSolution", parsed.getOrDefault("correctSolution", "")));
            return List.of(buildDraftItem(moduleId, ModuleStage.TEST.name(),
                    ContentItemType.SPOT_MISTAKE.name(), contentJson, answerJson, 200));
        } catch (Exception e) {
            log.error("[CentreRegen] spot-mistake failed moduleId={}: {}", moduleId, e.getMessage());
            return List.of();
        }
    }

    private List<ModuleContentItem> generateChallengesDraft(
            String moduleId, String content, String level, String subject, String tier, String guidanceSection) {
        int n = "CENTRE".equals(tier) ? 3 : 1;
        String prompt = """
                Generate %d application questions that test whether a %s student can USE these concepts.
                Include word problems where possible.%s

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"question":"...","answer":"...","explanation":"...","difficulty":"easy"}]
                """.formatted(n, level, guidanceSection, content);

        try {
            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "centre-regen-challenges");
            String json = extractJson(raw, '[', ']');
            List<Map<String, Object>> parsed = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<ModuleContentItem> items = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                String contentJson = objectMapper.writeValueAsString(Map.of(
                        "question", parsed.get(i).getOrDefault("question", ""),
                        "difficulty", parsed.get(i).getOrDefault("difficulty", "easy")));
                String answerJson = objectMapper.writeValueAsString(Map.of(
                        "answer", parsed.get(i).getOrDefault("answer", ""),
                        "explanation", parsed.get(i).getOrDefault("explanation", "")));
                items.add(buildDraftItem(moduleId, ModuleStage.TEST.name(),
                        ContentItemType.CHALLENGE.name(), contentJson, answerJson, 300 + i));
            }
            return items;
        } catch (Exception e) {
            log.error("[CentreRegen] challenges failed moduleId={}: {}", moduleId, e.getMessage());
            return List.of();
        }
    }

    private ModuleContentItem buildDraftItem(
            String moduleId, String stage, String type,
            String contentJson, String answerJson, int sortOrder) {
        ModuleContentItem item = new ModuleContentItem();
        item.setId(com.pally.shared.util.IdGenerator.newId());
        item.setModuleId(moduleId);
        item.setStage(stage);
        item.setType(type);
        item.setContentJson(contentJson);
        item.setAnswerJson(answerJson);
        item.setSortOrder(sortOrder);
        item.setTierRequired("FREE");
        item.setCreatedAt(java.time.Instant.now());
        item.setStatus("DRAFT");
        return item;
    }

    /**
     * Generates adaptive PROVE questions based on TEST results.
     * Targets concepts the student scored poorly on.
     */
    // A2: NOT @Transactional — the Gemini call runs with no open transaction; the
    // count + persist happen in ModuleWriter.appendProveItems.
    public List<ModuleContentItem> generateProveQuestions(
            LearningModule module,
            WikiPage page,
            List<ModuleProgress> testResults,
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
            // Diagnostic: PROVE-gen has been returning 0 items in prod, stalling
            // module completion. Log the raw model output when the parse is empty
            // so we can see WHAT the model actually returned.
            if (parsed.isEmpty()) {
                log.warn("[Module] PROVE-gen parsed 0 items module={} rawLen={} raw(head)={}",
                        module.getId(), raw == null ? 0 : raw.length(),
                        raw == null ? "null"
                                : raw.substring(0, Math.min(500, raw.length()))
                                     .replaceAll("\\s+", " "));
            }

            List<ModuleContentItem> items = new ArrayList<>();
            for (Map<String, Object> q : parsed) {
                ModuleContentItem item = new ModuleContentItem();
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

                item.setTierRequired(tier);
                item.setCreatedAt(Instant.now());
                items.add(item);
            }

            // sortOrder + persist in a short transaction (count is read there too).
            List<ModuleContentItem> saved = moduleWriter.appendProveItems(module.getId(), items);
            log.info("[Module] Generated {} PROVE questions for module={}",
                    saved.size(), module.getId());
            return saved;

        } catch (Exception e) {
            log.error("[Module] Failed to generate PROVE questions for module={}",
                    module.getId(), e);
            return List.of();
        }
    }

    // ── LEARN: micro-cards ───────────────────────────────────────────────

    private List<ModuleContentItem> generateMicroCards(
            String moduleId, String content, String level, String subject, String tier) {
        int n = "CENTRE".equals(tier) ? 6 : 4;

        String prompt = """
                Split this educational content into %d bite-size concept cards for a %s student studying %s.
                Each card covers ONE concept, under 60 words, with key terms in bold.

                Content:
                %s

                Reply ONLY with a JSON array:
                [{"title":"...","body":"...","keyTerms":["..."]}]
                """.formatted(n, level, subject, content);

        try {
            // B2: higher token budget + lenient salvage of complete elements before a
            // truncation point + one retry — so a truncated response no longer drops
            // the entire LEARN batch (module shipping without its micro-cards).
            List<Map<String, Object>> parsed =
                    robustJsonArray(MICRO_CARD_TOKENS, prompt, "module-learn-gen");

            List<ModuleContentItem> items = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                ModuleContentItem item = new ModuleContentItem();
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

    private List<ModuleContentItem> generateHotTakes(
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

            List<ModuleContentItem> items = new ArrayList<>();
            int offset = 100; // hot takes start at sort_order 100
            for (int i = 0; i < parsed.size(); i++) {
                ModuleContentItem item = new ModuleContentItem();
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

    private List<ModuleContentItem> generateSpotMistake(
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

            ModuleContentItem item = new ModuleContentItem();
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

    private List<ModuleContentItem> generateChallenges(
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

            List<ModuleContentItem> items = new ArrayList<>();
            int offset = 300; // challenges start at sort_order 300
            for (int i = 0; i < parsed.size(); i++) {
                ModuleContentItem item = new ModuleContentItem();
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

    /**
     * Resolves the content tier for module generation.
     * Centre avatars always get CENTRE-depth content (the centre pays).
     * For personal avatars, Pro/Max/Family subscribers get full CENTRE-depth;
     * Free/Spark subscribers get limited FREE-depth.
     */
    String resolveContentTier(Avatar avatar) {
        if (avatar.isCentreAvatar()) {
            return "CENTRE";
        }
        try {
            SubscriptionTier subTier = premiumService.resolveTier(avatar.getUserId());
            return switch (subTier) {
                case PRO, MAX, FAMILY -> "CENTRE";
                case FREE -> "FREE";
            };
        } catch (Exception e) {
            log.warn("[Module] Failed to resolve subscription tier for user={}, defaulting to FREE: {}",
                    avatar.getUserId(), e.getMessage());
            return "FREE";
        }
    }

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

    // ── Robust JSON-array parsing (B2) ───────────────────────────────────────────

    /// Calls the model and parses a JSON array of objects, tolerating truncation:
    /// the whole-array parse is tried first, then complete top-level objects are
    /// salvaged from a cut-off response. Retries the call ONCE if nothing parsed.
    private List<Map<String, Object>> robustJsonArray(int tokens, String prompt, String label) {
        List<Map<String, Object>> last = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = geminiCompletion.complete(tokens, prompt, label);
            last = parseJsonObjectsLenient(raw);
            if (!last.isEmpty()) {
                return last;
            }
            log.warn("[Module] {} produced no parseable JSON objects (attempt {}/2)", label, attempt);
        }
        return last;
    }

    /// Parse a JSON array of objects; if the array is truncated (the common Gemini
    /// failure), salvage every COMPLETE top-level object before the cut-off instead of
    /// discarding the whole response.
    List<Map<String, Object>> parseJsonObjectsLenient(String raw) {
        String json = extractJson(raw, '[', ']');
        try {
            List<Map<String, Object>> all = objectMapper.readValue(json, new TypeReference<>() {});
            if (all != null && !all.isEmpty()) {
                return all;
            }
        } catch (Exception truncated) {
            // fall through to element-level salvage
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String obj : extractBalancedObjects(raw)) {
            try {
                out.add(objectMapper.readValue(obj, new TypeReference<>() {}));
            } catch (Exception skip) {
                // a malformed / incomplete object — skip just this one
            }
        }
        if (!out.isEmpty()) {
            log.info("[Module] Salvaged {} complete item(s) from a truncated JSON response", out.size());
        }
        return out;
    }

    /// Returns every balanced, top-level {...} object in {@code s}, honouring quoted
    /// strings + escapes. An unbalanced trailing object (the truncation) is skipped.
    static List<String> extractBalancedObjects(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int depth = 0;
        int start = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(s.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }
}
