package com.pally.domain.module;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pally.domain.avatar.Avatar;
import com.pally.shared.json.JsonExtraction;
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
        allItems.addAll(generateMicroCards(module.getId(), content, level, subject, tier, "", avatar.getId())); // LEARN
        allItems.addAll(generateHotTakes(module.getId(), content, level, subject, tier, "", avatar.getId()));    // TEST
        allItems.addAll(generateSpotMistake(module.getId(), content, level, subject, "", avatar.getId()));
        allItems.addAll(generateChallenges(module.getId(), content, level, subject, tier, "", avatar.getId()));
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
        allItems.addAll(generateMicroCards(module.getId(), content, level, subject, tier, guidanceSection, avatar.getId()));
        allItems.addAll(generateHotTakes(module.getId(), content, level, subject, tier, guidanceSection, avatar.getId()));
        allItems.addAll(generateSpotMistake(module.getId(), content, level, subject, guidanceSection, avatar.getId()));
        allItems.addAll(generateChallenges(module.getId(), content, level, subject, tier, guidanceSection, avatar.getId()));
        // The merged generators build LIVE-style items; the teacher regenerate path marks them
        // DRAFT here (previously buildDraftItem set status="DRAFT" per item), so a regen preview
        // is never persisted as live content.
        allItems.forEach(i -> i.setStatus("DRAFT"));
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

                Generate exactly %d prove-it questions. Each targets ONE specific concept.
                The student must answer in 1-3 sentences to demonstrate understanding.
                Prioritize concepts the student scored poorly on; if they did well,
                still generate reinforcement questions on the key concepts.

                Output ONLY the JSON array — no prose, no preamble, no markdown fences.
                Start your reply with [ and end with ]. Shape:
                [{"question":"...","targetConcept":"...","expectedKeyPoints":["..."],"difficulty":"easy/medium/hard"}]
                """.formatted(page.getTitle(), testSummary, n);

        try {
            // Robust parse (retry + salvage from prose/truncation) — the fragile
            // extractJson+readValue path was silently yielding [] on prose output,
            // which stalled module completion.
            List<Map<String, Object>> parsed = robustJsonArray(MAX_TOKENS, prompt, "module-prove-gen", page.getAvatarId());

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

            if (items.isEmpty()) {
                // Never leave PROVE empty — a module with 0 prove items can never
                // reach COMPLETE (student stuck; weakness trigger never fires). One
                // reinforcement question keeps the loop closeable even when the model
                // returns nothing or the student aced TEST (no weak concepts).
                items.add(fallbackProveItem(module, page, tier));
                log.warn("[Module] PROVE-gen yielded 0 items for module={} — using a reinforcement fallback",
                        module.getId());
            }

            // sortOrder + persist in a short transaction (count is read there too).
            List<ModuleContentItem> saved = moduleWriter.appendProveItems(module.getId(), items);
            log.info("[Module] Generated {} PROVE questions for module={}",
                    saved.size(), module.getId());
            return saved;

        } catch (Exception e) {
            // Even on hard failure, persist a fallback so the module stays completable.
            log.error("[Module] Failed to generate PROVE questions for module={} — using fallback",
                    module.getId(), e);
            try {
                return moduleWriter.appendProveItems(module.getId(),
                        List.of(fallbackProveItem(module, page, tier)));
            } catch (Exception persistFail) {
                log.error("[Module] Fallback PROVE persist also failed module={}",
                        module.getId(), persistFail);
                return List.of();
            }
        }
    }

    /** A generic reinforcement PROVE question so a module is never stuck with 0 items. */
    private ModuleContentItem fallbackProveItem(LearningModule module, WikiPage page, String tier) {
        ModuleContentItem item = new ModuleContentItem();
        item.setId(IdGenerator.newId());
        item.setModuleId(module.getId());
        item.setStage(ModuleStage.PROVE.name());
        item.setType(ContentItemType.PROVE_QUESTION.name());
        String concept = page.getTitle();
        try {
            item.setContentJson(objectMapper.writeValueAsString(Map.of(
                    "question", "In your own words, explain the main idea of \"" + concept
                            + "\" and give one worked example.",
                    "targetConcept", concept,
                    "expectedKeyPoints", List.of(concept),
                    "difficulty", "medium")));
            item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                    "expectedKeyPoints", List.of(concept),
                    "targetConcept", concept)));
        } catch (Exception e) {
            item.setContentJson("{\"question\":\"Explain the main idea in your own words.\"}");
            item.setAnswerJson("{\"expectedKeyPoints\":[],\"targetConcept\":\"\"}");
        }
        item.setTierRequired(tier);
        item.setCreatedAt(Instant.now());
        return item;
    }

    /// Non-empty fallback for the STAGE generators (LEARN/TEST) — mirrors
    /// fallbackProveItem so NO stage can ship empty and strand the student on a
    /// transient model failure/truncation. Enforced by ModuleStageFallbackGuardTest.
    ModuleContentItem fallbackStageItem(String moduleId, ModuleStage stage,
            ContentItemType type, String content, int sortOrder) {
        String concept = summarizeForFallback(content);
        ModuleContentItem item = new ModuleContentItem();
        item.setId(IdGenerator.newId());
        item.setModuleId(moduleId);
        item.setStage(stage.name());
        item.setType(type.name());
        item.setSortOrder(sortOrder);
        item.setTierRequired("FREE");
        item.setCreatedAt(Instant.now());
        try {
            switch (type) {
                case MICRO_CARD -> item.setContentJson(objectMapper.writeValueAsString(Map.of(
                        "title", "Key idea", "body", concept, "keyTerms", List.of())));
                case HOT_TAKE -> {
                    item.setContentJson(objectMapper.writeValueAsString(Map.of(
                            "statement", "This lesson is about: " + concept)));
                    item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                            "isTrue", true, "explanation", "Review the material above.")));
                }
                case SPOT_MISTAKE -> {
                    item.setContentJson(objectMapper.writeValueAsString(Map.of(
                            "problem", "Review this idea and check your understanding.",
                            "wrongSolution", concept)));
                    item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                            "errorDescription", "Re-read the notes to confirm this.",
                            "correctSolution", concept)));
                }
                case CHALLENGE -> {
                    // Generic stem — do NOT present a raw content slice as the question
                    // (Bug 3: a fallback showed a truncated markdown fragment as the stem).
                    item.setContentJson(objectMapper.writeValueAsString(Map.of(
                            "question", "In your own words, explain the key idea from your notes for this lesson.",
                            "difficulty", "easy")));
                    item.setAnswerJson(objectMapper.writeValueAsString(Map.of(
                            "answer", concept, "explanation", "See the notes above.")));
                }
                default -> item.setContentJson("{}");
            }
        } catch (Exception e) {
            item.setContentJson("{}");
        }
        return item;
    }

    /// Clean a raw wiki-content slice into a student-safe fallback summary: strip markdown
    /// artifacts (so `* **`, `#`, backticks, links never render as a question stem / card
    /// body) and truncate at a WORD boundary, never mid-word. Package-private for testing.
    static String summarizeForFallback(String content) {
        if (content == null || content.isBlank()) return "this topic";
        String c = content
                .replaceAll("[*_`>#~\\[\\]]", " ") // markdown emphasis/heading/quote/code/link syntax
                .replaceAll("\\s+", " ")
                .strip();
        if (c.isBlank()) return "this topic";
        if (c.length() <= 140) return c;
        // Truncate at the last word boundary within the window, not mid-word.
        String cut = c.substring(0, 140);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > 80) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip() + "…";
    }

    // ── LEARN: micro-cards ───────────────────────────────────────────────

    // MERGED student(LIVE)+teacher(DRAFT) generator. guidanceSection is "" for the student
    // path (reproduces the old main prompt byte-for-byte) and the teacher-guidance block for
    // the centre regenerate path. Items are identical either way (draft-ness lives in the
    // persist path, not the item), so one method replaces the old a/b twins.
    List<ModuleContentItem> generateMicroCards(
            String moduleId, String content, String level, String subject, String tier, String guidanceSection, String avatarId) {
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
            // B2: higher token budget + lenient salvage of complete elements before a
            // truncation point + one retry — so a truncated response no longer drops
            // the entire LEARN batch (module shipping without its micro-cards).
            List<Map<String, Object>> parsed = robustJsonArray(MICRO_CARD_TOKENS, prompt,
                    guidanceSection.isBlank() ? "module-learn-gen" : "centre-regen-learn", avatarId);

            List<ModuleContentItem> items = new ArrayList<>();
            for (int i = 0; i < parsed.size(); i++) {
                // Drop wrong-shape/blank cards: a non-empty salvage can still yield an object
                // missing title/body → a blank micro-card. Skip; empty-after-skip → fallback below.
                if (!hasNonBlank(parsed.get(i), "title", "body")) continue;
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
            return items.isEmpty()
                    ? List.of(fallbackStageItem(moduleId, ModuleStage.LEARN,
                            ContentItemType.MICRO_CARD, content, 0))
                    : items;
        } catch (Exception e) {
            log.error("[Module] Failed to generate micro-cards for module={} — using fallback",
                    moduleId, e);
            return List.of(fallbackStageItem(moduleId, ModuleStage.LEARN,
                    ContentItemType.MICRO_CARD, content, 0));
        }
    }

    // ── TEST: hot takes ──────────────────────────────────────────────────

    List<ModuleContentItem> generateHotTakes(
            String moduleId, String content, String level, String subject, String tier, String guidanceSection, String avatarId) {
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
            List<Map<String, Object>> parsed = robustJsonArray(MAX_TOKENS, prompt,
                    guidanceSection.isBlank() ? "module-hottake-gen" : "centre-regen-hottake", avatarId);

            List<ModuleContentItem> items = new ArrayList<>();
            int offset = 100; // hot takes start at sort_order 100
            for (int i = 0; i < parsed.size(); i++) {
                // Skip wrong-shape/blank statements (non-empty salvage missing the fields).
                if (!hasNonBlank(parsed.get(i), "statement", "explanation")) continue;
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
            return items.isEmpty()
                    ? List.of(fallbackStageItem(moduleId, ModuleStage.TEST,
                            ContentItemType.HOT_TAKE, content, 100))
                    : items;
        } catch (Exception e) {
            log.error("[Module] Failed to generate hot takes for module={} — using fallback",
                    moduleId, e);
            return List.of(fallbackStageItem(moduleId, ModuleStage.TEST,
                    ContentItemType.HOT_TAKE, content, 100));
        }
    }

    // ── TEST: spot the mistake ───────────────────────────────────────────

    List<ModuleContentItem> generateSpotMistake(
            String moduleId, String content, String level, String subject, String guidanceSection, String avatarId) {

        String prompt = """
                Write ONE plausible but WRONG worked solution for a problem from this content.
                Introduce a common %s-student misconception. The student must find the error.%s

                Content:
                %s

                Reply ONLY with JSON:
                {"problem":"...","wrongSolution":"...","errorDescription":"...","correctSolution":"..."}
                """.formatted(level, guidanceSection, content);

        try {
            Map<String, Object> parsed = robustJsonObject(MAX_TOKENS, prompt,
                    guidanceSection.isBlank() ? "module-spotmistake-gen" : "centre-regen-spotmistake", avatarId);
            // Single-object guard: a total OR partial parse would build a blank/half-blank
            // SPOT_MISTAKE (getOrDefault→""). Require all four fields; else a real fallback.
            if (!hasNonBlank(parsed, "problem", "wrongSolution", "errorDescription", "correctSolution")) {
                return List.of(fallbackStageItem(moduleId, ModuleStage.TEST,
                        ContentItemType.SPOT_MISTAKE, content, 200));
            }

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
            log.error("[Module] Failed to generate spot-mistake for module={} — using fallback",
                    moduleId, e);
            return List.of(fallbackStageItem(moduleId, ModuleStage.TEST,
                    ContentItemType.SPOT_MISTAKE, content, 200));
        }
    }

    // ── TEST: challenges ─────────────────────────────────────────────────

    List<ModuleContentItem> generateChallenges(
            String moduleId, String content, String level, String subject, String tier, String guidanceSection, String avatarId) {
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
            List<Map<String, Object>> parsed = robustJsonArray(MAX_TOKENS, prompt,
                    guidanceSection.isBlank() ? "module-challenge-gen" : "centre-regen-challenge", avatarId);

            List<ModuleContentItem> items = new ArrayList<>();
            int offset = 300; // challenges start at sort_order 300
            for (int i = 0; i < parsed.size(); i++) {
                // Skip wrong-shape/blank questions (non-empty salvage missing the fields).
                if (!hasNonBlank(parsed.get(i), "question", "answer", "explanation")) continue;
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
            return items.isEmpty()
                    ? List.of(fallbackStageItem(moduleId, ModuleStage.TEST,
                            ContentItemType.CHALLENGE, content, 300))
                    : items;
        } catch (Exception e) {
            log.error("[Module] Failed to generate challenges for module={} — using fallback",
                    moduleId, e);
            return List.of(fallbackStageItem(moduleId, ModuleStage.TEST,
                    ContentItemType.CHALLENGE, content, 300));
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

    // Delegates to the shared JsonExtraction (one robust extractor for all AI classes).
    String extractJson(String raw, char openChar, char closeChar) {
        return JsonExtraction.extractJson(raw, openChar, closeChar);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ── Robust JSON-array parsing (B2) ───────────────────────────────────────────

    /// Calls the model and parses a JSON array of objects, tolerating truncation:
    /// the whole-array parse is tried first, then complete top-level objects are
    /// salvaged from a cut-off response. Retries the call ONCE if nothing parsed.
    private List<Map<String, Object>> robustJsonArray(int tokens, String prompt, String label,
                                                      String avatarId) {
        List<Map<String, Object>> last = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = geminiCompletion.complete(tokens, prompt, label, avatarId);
            last = parseJsonObjectsLenient(raw);
            if (!last.isEmpty()) {
                return last;
            }
            log.warn("[Module] {} produced no parseable JSON objects (attempt {}/2)", label, attempt);
        }
        return last;
    }

    /// Single-object sibling of {@link #robustJsonArray}: retries once so a
    /// prose/markdown-wrapped object response doesn't silently yield an empty map.
    private Map<String, Object> robustJsonObject(int tokens, String prompt, String label,
                                                 String avatarId) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw = geminiCompletion.complete(tokens, prompt, label, avatarId);
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        extractJson(raw, '{', '}'), new TypeReference<>() {});
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // fall through to retry
            }
            log.warn("[Module] {} produced no parseable JSON object (attempt {}/2)", label, attempt);
        }
        return Map.of();
    }

    /// Field-level guard for SINGLE-OBJECT generators. robustJsonObject returns Map.of()
    /// on total failure AND can return a PARTIAL map (some keys present, others missing —
    /// e.g. a spot-mistake with a problem but a blank answer half). A map-level isEmpty()
    /// check misses the partial case and would persist a structurally-complete-but-blank
    /// item (a card with no answer to reveal). Require every listed field be present and a
    /// non-blank String before building the real item; otherwise route to a fallback.
    private static boolean hasNonBlank(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (!(m.get(k) instanceof String s) || s.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /// Parse a JSON array of objects; if the array is truncated (the common Gemini
    /// failure), salvage every COMPLETE top-level object before the cut-off instead of
    /// discarding the whole response.
    // Delegates to the shared JsonExtraction (truncation-salvage lives there now).
    List<Map<String, Object>> parseJsonObjectsLenient(String raw) {
        return JsonExtraction.parseObjects(objectMapper, raw);
    }
}
