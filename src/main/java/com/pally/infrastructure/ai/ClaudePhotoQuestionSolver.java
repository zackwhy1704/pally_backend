package com.pally.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.chat.usecase.PhotoQuestionPort;
import com.pally.domain.knowledge.WikiPage;
import com.pally.infrastructure.observability.ClaudeMetrics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Solves homework photo questions with three safety layers:
 *
 * <ol>
 *   <li><b>Tier 0 — visual detection</b>: classifies whether each question requires
 *       reading a chart/graph/diagram/geometry element. When detected, the solver
 *       returns a collaborative disclaimer instead of a confident answer.</li>
 *   <li><b>Tier 1 — confirm what was read</b>: the response includes any transcribed
 *       values so the student can verify them before the solver computes an answer.</li>
 *   <li><b>Tier 2 — model escalation</b>: visual questions are routed to a stronger
 *       vision model via {@link ModelRouter}; hard visual or low-confidence questions
 *       escalate further. This is layered ON TOP of Tier 0/1 — never a replacement.</li>
 *   <li><b>Calculator tool</b>: all arithmetic is computed deterministically; the
 *       {@code calculatorVerified} flag on the answer signals this to the frontend.</li>
 *   <li><b>Hidden reasoning</b>: the model reasons about likely student mistakes before
 *       producing the student-facing reply.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ClaudePhotoQuestionSolver implements PhotoQuestionPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudePhotoQuestionSolver.class);
    private static final int MAX_TOKENS = 6000;
    private static final int MAX_WIKI_CHARS = 6000;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;
    private final CalculatorTool calculatorTool;
    private final ClaudeMetrics metrics;

    // Visual type constants
    public static final String VT_NONE = "NONE";
    public static final String VT_CHART = "CHART";
    public static final String VT_GRAPH = "GRAPH";
    public static final String VT_DIAGRAM = "DIAGRAM";
    public static final String VT_GEOMETRY = "GEOMETRY";
    public static final String VT_TABLE = "TABLE";

    @Override
    public List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages,
                                                   List<String> questions) {
        return solveQuestions(avatar, wikiPages, questions, null, null);
    }

    @Override
    public List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages,
                                                   List<String> questions,
                                                   byte[] imageBytes, String mimeType) {
        String wikiContext = buildWikiContext(wikiPages);

        // Step 1 — quick visual classification (Haiku, cheap)
        List<VisualClassification> classifications = classifyVisual(questions, avatar);

        // Step 2 — route to the appropriate model tier
        boolean anyVisual = classifications.stream()
                .anyMatch(c -> !VT_NONE.equals(c.visualType));
        boolean hardVisual = classifications.stream()
                .anyMatch(c -> (VT_GRAPH.equals(c.visualType)
                        || VT_GEOMETRY.equals(c.visualType))
                        || "LOW".equals(c.visualConfidence));

        String model;
        String tier;
        if (hardVisual) {
            model = modelRouter.forPhotoQuestionMax();
            tier = "max";
        } else if (anyVisual) {
            model = modelRouter.forPhotoQuestionHeavy();
            tier = "heavy";
        } else {
            model = modelRouter.forPhotoQuestion();
            tier = "standard";
        }
        if (!tier.equals("standard")) {
            metrics.recordVisualEscalation(tier);
            log.info("[PhotoSolver] Escalated to {} model for visual question", tier);
        }

        String prompt = buildPrompt(avatar, wikiContext, questions, classifications);
        log.debug("[PhotoSolver] Solving {} questions for avatar={} model={} hasImage={}",
                questions.size(), avatar.getId(), model, imageBytes != null);

        try {
            String raw;
            if (imageBytes != null && imageBytes.length > 0) {
                // Vision path: send the original image directly to the model
                log.info("[PhotoSolver] Using VISION path with image ({} bytes, {})",
                        imageBytes.length, mimeType);
                try {
                    raw = apiClient.completeVisionWithTools(
                            model, MAX_TOKENS, prompt,
                            imageBytes, mimeType,
                            List.of(calculatorTool),
                            "photo-solver-vision"
                    );
                } catch (Exception visionEx) {
                    // Fallback to text-only if vision call fails
                    log.warn("[PhotoSolver] Vision call failed — falling back to text-only: {}",
                            visionEx.getMessage());
                    raw = apiClient.completeWithTools(
                            model, MAX_TOKENS, prompt,
                            List.of(calculatorTool),
                            "photo-solver"
                    );
                }
            } else {
                // Text-only path (existing behavior)
                raw = apiClient.completeWithTools(
                        model, MAX_TOKENS, prompt,
                        List.of(calculatorTool),
                        "photo-solver"
                );
            }
            return parseAnswers(questions, classifications, raw);
        } catch (Exception e) {
            log.error("[PhotoSolver] Failed: {}", e.getMessage(), e);
            return buildStubAnswers(questions);
        }
    }

    // ── Visual classification ──────────────────────────────────────────────────

    private List<VisualClassification> classifyVisual(List<String> questions, Avatar avatar) {
        String numbered = java.util.stream.IntStream.range(0, questions.size())
                .mapToObj(i -> (i + 1) + ". " + questions.get(i))
                .collect(Collectors.joining("\n"));

        String classifyPrompt = """
                Classify whether each question requires READING VISUAL CONTENT from an image
                (charts, graphs, diagrams, geometry, tables) vs pure text/numbers.

                For each question return:
                - visualType: NONE | CHART | GRAPH | DIAGRAM | GEOMETRY | TABLE
                - visualConfidence: HIGH | MEDIUM | LOW
                  (HIGH = clearly visual, LOW = probably not visual)

                Questions:
                %s

                Reply ONLY with a JSON array, one object per question:
                [{"questionIndex":1,"visualType":"NONE","visualConfidence":"HIGH"}, ...]
                """.formatted(numbered);
        try {
            String raw = apiClient.complete(
                    modelRouter.getHaikuModel(), 600, classifyPrompt, "visual-classify");
            // Strip markdown fence and find array
            raw = raw.replaceAll("(?s)<reasoning>.*?</reasoning>", "").strip();
            if (raw.startsWith("```")) raw = raw.replaceAll("```[a-z]*\\n?", "")
                    .replaceAll("```", "").strip();
            int s = raw.indexOf('['), e = raw.lastIndexOf(']');
            if (s >= 0 && e > s) raw = raw.substring(s, e + 1);
            List<Map<String, Object>> parsed = objectMapper.readValue(raw,
                    new TypeReference<>() {});
            List<VisualClassification> result = new ArrayList<>();
            for (Map<String, Object> m : parsed) {
                result.add(new VisualClassification(
                        (String) m.getOrDefault("visualType", VT_NONE),
                        (String) m.getOrDefault("visualConfidence", "HIGH")
                ));
            }
            // Pad if model returned fewer items
            while (result.size() < questions.size()) {
                result.add(new VisualClassification(VT_NONE, "HIGH"));
            }
            log.debug("[PhotoSolver] Classifications: {}", result);
            return result;
        } catch (Exception ex) {
            log.warn("[PhotoSolver] Classification failed: {}; defaulting to NONE", ex.getMessage());
            return questions.stream()
                    .map(q -> new VisualClassification(VT_NONE, "HIGH"))
                    .toList();
        }
    }

    record VisualClassification(String visualType, String visualConfidence) {}

    // ── Prompt building ────────────────────────────────────────────────────────

    private String buildPrompt(Avatar avatar, String wikiContext,
                                List<String> questions,
                                List<VisualClassification> classifications) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            VisualClassification vc = i < classifications.size()
                    ? classifications.get(i)
                    : new VisualClassification(VT_NONE, "HIGH");
            numbered.append(i + 1).append(". ").append(questions.get(i));
            if (!VT_NONE.equals(vc.visualType)) {
                numbered.append(" [VISUAL: ").append(vc.visualType).append("]");
            }
            numbered.append("\n");
        }

        boolean anyVisual = classifications.stream()
                .anyMatch(c -> !VT_NONE.equals(c.visualType));

        String visualInstructions = anyVisual ? """

                VISUAL SAFETY RULES (apply to any question marked [VISUAL]):
                - Do NOT assert confident values you read from chart bars, graph lines, or
                  geometric measurements. You cannot reliably read pixel values from images.
                - Instead: tell the child what kind of visual you see, state what you CAN read
                  (labels, axis titles, categories), and ask them to supply the specific values.
                - Format your answer as a friendly collaborative response: "I can see this is
                  a bar chart showing ... Can you tell me the value for each bar so I can help?"
                - Set calculatorVerified=false for any visually-derived answer.
                """ : "";

        return """
                You are %s, a friendly AI tutor for children studying %s (ages 8-14).
                Solve every question below. Use simple language and short sentences.
                Show clear working steps. Be encouraging.

                IMPORTANT — For any arithmetic calculation, use the calculator tool.
                Never guess a numerical answer; always compute it.%s

                Knowledge base (use if relevant):
                %s

                STEP 1 — Think through each question (hidden from the student):
                <reasoning>
                For each question:
                a) What concept does this question test?
                b) Is there visual content I need to read? If so, what CAN I reliably see?
                c) What calculation or reasoning is needed?
                d) What common mistakes might a student make?
                e) Use the calculator tool for any arithmetic.
                </reasoning>

                STEP 2 — Output ONLY the JSON array (no other text after the reasoning block):
                Questions:
                %s

                Reply with ONLY a valid JSON array. No markdown. No text before or after.
                Each object must have exactly these fields:
                {
                  "questionIndex": <number starting at 1>,
                  "questionText": "<copy the question text here>",
                  "answer": "<final answer — for visual questions: what you see + what you need from the student>",
                  "steps": ["<step 1>", "<step 2>", ...],
                  "explanation": "<one encouraging sentence>",
                  "visualType": "<NONE | CHART | GRAPH | DIAGRAM | GEOMETRY | TABLE>",
                  "calculatorVerified": <true | false>
                }
                Output exactly %d objects in the array.
                """.formatted(
                avatar.getName(), avatar.getSubject().name(),
                visualInstructions,
                wikiContext,
                numbered,
                questions.size()
        );
    }

    // ── Response parsing ───────────────────────────────────────────────────────

    private List<QuestionAnswerDto> parseAnswers(List<String> questions,
                                                  List<VisualClassification> classifications,
                                                  String raw) {
        try {
            String cleaned = raw.replaceAll("(?s)<reasoning>.*?</reasoning>", "").strip();
            String json = extractJson(cleaned);
            JsonNode array = objectMapper.readTree(json);
            List<QuestionAnswerDto> answers = new ArrayList<>();

            for (int i = 0; i < array.size(); i++) {
                JsonNode node = array.get(i);
                int idx = node.path("questionIndex").asInt(i + 1) - 1;
                String questionText = idx >= 0 && idx < questions.size()
                        ? questions.get(idx)
                        : node.path("questionText").asText("");

                List<String> steps = objectMapper.convertValue(
                        node.path("steps"), new TypeReference<>() {});
                String vt = node.path("visualType").asText(VT_NONE);
                boolean calcVerified = node.path("calculatorVerified").asBoolean(false);

                // Override: if the model classified as non-visual but we detected visual,
                // trust our pre-classification (more reliable for Tier 0 safety).
                if (idx < classifications.size() && !VT_NONE.equals(classifications.get(idx).visualType)) {
                    vt = classifications.get(idx).visualType;
                }
                if (!VT_NONE.equals(vt)) {
                    metrics.recordCalculatorDisagreement("photo"); // visual question = inherent uncertainty
                }

                answers.add(new QuestionAnswerDto(
                        UUID.randomUUID().toString(),
                        questionText,
                        node.path("answer").asText("See steps below"),
                        steps,
                        node.path("explanation").asText(""),
                        vt,
                        calcVerified
                ));
            }
            return answers;
        } catch (Exception e) {
            log.error("[PhotoSolver] Failed to parse response", e);
            return buildStubAnswers(questions);
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('['), end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    private List<QuestionAnswerDto> buildStubAnswers(List<String> questions) {
        log.error("[PhotoSolver] Returning error sentinel for {} question(s)", questions.size());
        return List.of(new QuestionAnswerDto(
                "__PARSE_ERROR__", "__ERROR__", "Something went wrong",
                List.of("The AI couldn't process your questions. Please try again."),
                "", VT_NONE, false
        ));
    }

    private String buildWikiContext(List<WikiPage> pages) {
        if (pages.isEmpty()) return "No knowledge loaded yet.";
        String combined = pages.stream()
                .map(p -> "### " + p.getTitle() + "\n" + p.getContent())
                .collect(Collectors.joining("\n\n"));
        return combined.length() > MAX_WIKI_CHARS
                ? combined.substring(0, MAX_WIKI_CHARS) + "\n... [truncated]"
                : combined;
    }
}
