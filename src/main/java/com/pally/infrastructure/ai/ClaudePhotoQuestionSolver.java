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

@Component
@RequiredArgsConstructor
public class ClaudePhotoQuestionSolver implements PhotoQuestionPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudePhotoQuestionSolver.class);
    private static final int MAX_TOKENS = 6000;
    private static final int MAX_WIKI_CHARS = 6000;

    @org.springframework.beans.factory.annotation.Value("${claude.api.model}")
    private String model;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Override
    public List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages, List<String> questions) {
        String wikiContext = buildWikiContext(wikiPages);
        String prompt = buildPrompt(avatar, wikiContext, questions);

        log.debug("Solving {} questions for avatar={}", questions.size(), avatar.getId());

        try {
            String raw = apiClient.complete(model, MAX_TOKENS, prompt);
            log.debug("[PhotoSolver] Raw response ({} chars): {}", raw.length(),
                    raw.substring(0, Math.min(200, raw.length())));
            return parseAnswers(questions, raw);
        } catch (Exception e) {
            log.error("[PhotoSolver] Claude call failed: {} — {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return buildStubAnswers(questions);
        }
    }

    private String buildPrompt(Avatar avatar, String wikiContext, List<String> questions) {
        String numberedQuestions = java.util.stream.IntStream.range(0, questions.size())
                .mapToObj(i -> (i + 1) + ". " + questions.get(i))
                .collect(Collectors.joining("\n"));

        return """
                You are %s, a friendly AI tutor for children studying %s (ages 8-14).
                Solve every question below. Use simple language and short sentences.
                Show clear working steps. Be encouraging.

                Knowledge base (use if relevant):
                %s

                Questions:
                %s

                IMPORTANT: Reply with ONLY a valid JSON array. No markdown. No text before or after the JSON.
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
            String json = extractJson(raw);
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
            log.error("Failed to parse Claude photo answer JSON", e);
            return buildStubAnswers(questions);
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private List<QuestionAnswerDto> buildStubAnswers(List<String> questions) {
        List<QuestionAnswerDto> stubs = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            stubs.add(new QuestionAnswerDto(
                    UUID.randomUUID().toString(),
                    questions.get(i),
                    "Unable to solve right now",
                    List.of("Something went wrong connecting to the AI tutor."),
                    "Sorry! There was a problem solving this question. Please try again in a moment."
            ));
        }
        return stubs;
    }

    private String buildWikiContext(List<WikiPage> pages) {
        if (pages.isEmpty()) { return "No knowledge loaded yet."; }
        String combined = pages.stream()
                .map(p -> "### " + p.getTitle() + "\n" + p.getContent())
                .collect(Collectors.joining("\n\n"));
        return combined.length() > MAX_WIKI_CHARS
                ? combined.substring(0, MAX_WIKI_CHARS) + "\n... [truncated]"
                : combined;
    }
}
