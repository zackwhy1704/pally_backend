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
    private static final String MODEL = "claude-3-5-sonnet-20241022";
    private static final int MAX_TOKENS = 2048;
    private static final int MAX_WIKI_CHARS = 6000;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Override
    public List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages, List<String> questions) {
        String wikiContext = buildWikiContext(wikiPages);
        String prompt = buildPrompt(avatar, wikiContext, questions);

        log.debug("Solving {} questions for avatar={}", questions.size(), avatar.getId());

        try {
            String raw = apiClient.complete(MODEL, MAX_TOKENS, prompt);
            return parseAnswers(questions, raw);
        } catch (Exception e) {
            log.error("Claude photo question solver failed, returning stubs", e);
            return buildStubAnswers(questions);
        }
    }

    private String buildPrompt(Avatar avatar, String wikiContext, List<String> questions) {
        String numberedQuestions = questions.stream()
                .map(q -> "- " + q)
                .collect(Collectors.joining("\n"));

        return """
                You are %s, a friendly AI tutor specialising in %s for children aged 8-14.
                A student has scanned their homework and needs help solving these questions.
                Use age-appropriate language, short sentences, and encouraging tone.

                Knowledge base:
                %s

                Questions to solve:
                %s

                Reply ONLY with a JSON array (no markdown, no explanation outside JSON):
                [
                  {
                    "questionIndex": 1,
                    "questionText": "the exact question text",
                    "answer": "the final answer",
                    "steps": ["Step 1: ...", "Step 2: ...", "Step 3: ..."],
                    "explanation": "brief encouraging explanation"
                  }
                ]
                """.formatted(
                avatar.getName(),
                avatar.getSubject().name(),
                wikiContext,
                numberedQuestions
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
                    "Great question! Let me think…",
                    List.of("Step 1: Read the question carefully", "Step 2: Identify the key information"),
                    "I'll need more context to give you the full answer — try asking me in the chat!"
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
