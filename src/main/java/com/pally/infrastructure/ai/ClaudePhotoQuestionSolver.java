package com.pally.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.chat.usecase.PhotoQuestionPort;
import com.pally.domain.knowledge.WikiPage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Solves homework questions using Claude with a deterministic calculator tool.
 *
 * <p>Accuracy improvements over the original:
 * <ol>
 *   <li><b>Calculator tool</b> — the model can call the calculator for any arithmetic,
 *       so final numeric answers are computed rather than predicted.</li>
 *   <li><b>Hidden reasoning</b> — the model reasons step-by-step about how a student
 *       might have reached their answer (Khan Academy technique) before producing the
 *       student-facing reply. The reasoning is in a delimited block that gets stripped.</li>
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

    @Override
    public List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages,
                                                  List<String> questions) {
        String wikiContext = buildWikiContext(wikiPages);
        String prompt = buildPrompt(avatar, wikiContext, questions);

        log.debug("[PhotoSolver] Solving {} questions for avatar={} with calculator tool",
                questions.size(), avatar.getId());

        try {
            // Pass the calculator tool so the model verifies arithmetic before stating answers.
            String raw = apiClient.completeWithTools(
                    modelRouter.forPhotoQuestion(),
                    MAX_TOKENS,
                    prompt,
                    List.of(calculatorTool),
                    "photo-solver"
            );
            log.debug("[PhotoSolver] Raw response ({} chars): {}",
                    raw.length(), raw.substring(0, Math.min(200, raw.length())));
            return parseAnswers(questions, raw);
        } catch (Exception e) {
            log.error("[PhotoSolver] Failed: {} — {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return buildStubAnswers(questions);
        }
    }

    private String buildPrompt(Avatar avatar, String wikiContext, List<String> questions) {
        String numberedQuestions = java.util.stream.IntStream.range(0, questions.size())
                .mapToObj(i -> (i + 1) + ". " + questions.get(i))
                .collect(Collectors.joining("\n"));

        // Part B — hidden reasoning: model thinks about how the student might have arrived
        // at their answer before producing the student-facing reply. The reasoning block
        // is stripped before parsing; it never reaches the child.
        return """
                You are %s, a friendly AI tutor for children studying %s (ages 8-14).
                Solve every question below. Use simple language and short sentences.
                Show clear working steps. Be encouraging.

                IMPORTANT — For any arithmetic calculation, use the calculator tool.
                Never guess a numerical answer; always compute it.

                Knowledge base (use if relevant):
                %s

                STEP 1 — Think through each question (this is hidden from the student):
                <reasoning>
                For each question:
                a) What concept does this question test?
                b) What calculation or reasoning is needed?
                c) What common mistakes might a student make?
                d) Use the calculator tool for any arithmetic to verify your answer.
                </reasoning>

                STEP 2 — Output ONLY the JSON array (no other text after the reasoning block):
                Questions:
                %s

                Reply with ONLY a valid JSON array. No markdown. No text before or after the JSON.
                Each object must have exactly these fields:
                {
                  "questionIndex": <number starting at 1>,
                  "questionText": "<copy the question text here>",
                  "answer": "<the final answer as a short string>",
                  "steps": ["<step 1>", "<step 2>", "<step 3 if needed>"],
                  "explanation": "<one encouraging sentence>"
                }
                Output exactly %d objects in the array, one per question.
                """.formatted(
                avatar.getName(),
                avatar.getSubject().name(),
                wikiContext,
                numberedQuestions,
                questions.size()
        );
    }

    private List<QuestionAnswerDto> parseAnswers(List<String> questions, String raw) {
        try {
            // Strip hidden reasoning block before parsing JSON
            String cleaned = raw.replaceAll("(?s)<reasoning>.*?</reasoning>", "").strip();
            String json = extractJson(cleaned);
            JsonNode array = objectMapper.readTree(json);
            List<QuestionAnswerDto> answers = new ArrayList<>();

            for (int i = 0; i < array.size(); i++) {
                JsonNode node = array.get(i);
                int idx = node.path("questionIndex").asInt(i + 1) - 1;
                String questionText = idx < questions.size()
                        ? questions.get(idx)
                        : node.path("questionText").asText("");

                List<String> steps = objectMapper.convertValue(
                        node.path("steps"), new TypeReference<>() {});

                answers.add(new QuestionAnswerDto(
                        UUID.randomUUID().toString(),
                        questionText,
                        node.path("answer").asText("See steps below"),
                        steps,
                        node.path("explanation").asText("")
                ));
            }
            return answers;
        } catch (Exception e) {
            log.error("[PhotoSolver] Failed to parse Claude response", e);
            return buildStubAnswers(questions);
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw;
    }

    private List<QuestionAnswerDto> buildStubAnswers(List<String> questions) {
        log.error("[PhotoSolver] Returning error sentinel for {} question(s)", questions.size());
        return List.of(new QuestionAnswerDto(
                "__PARSE_ERROR__",
                "__ERROR__",
                "Something went wrong",
                List.of("The AI couldn't process your questions. Please try again."),
                ""
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
