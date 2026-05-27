package com.pally.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ClaudeQuizGenerator implements QuizGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeQuizGenerator.class);

    private final ClaudeApiClient claudeApiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    @Override
    public List<QuizQuestion> generate(String avatarId, List<WikiPage> pages) {
        String material = pages.stream()
                .map(p -> p.getTitle() + ": " + p.getContent())
                .collect(Collectors.joining("\n\n"));

        String prompt = """
                Based on the following study material, generate 5 multiple-choice quiz questions.
                Each question should test understanding, not just memorisation.
                Questions must come directly from the provided material.

                Material:
                %s

                Reply ONLY with a JSON array (no markdown, no explanation):
                [{"question":"...","options":["A...","B...","C...","D..."],"correctIndex":0,"sourcePage":"slug","explanation":"..."}]
                """.formatted(material);

        try {
            String raw = claudeApiClient.complete(modelRouter.forQuizGeneration(), 2000, prompt);
            // Strip markdown code fences if present
            raw = raw.strip();
            if (raw.startsWith("```")) {
                raw = raw.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
            }

            List<Map<String, Object>> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
            List<QuizQuestion> questions = new ArrayList<>();
            for (Map<String, Object> q : parsed) {
                @SuppressWarnings("unchecked")
                List<String> opts = (List<String>) q.get("options");
                questions.add(new QuizQuestion(
                        IdGenerator.newId(),
                        avatarId,
                        (String) q.get("question"),
                        opts,
                        ((Number) q.get("correctIndex")).intValue(),
                        (String) q.getOrDefault("sourcePage", ""),
                        (String) q.getOrDefault("explanation", "")
                ));
            }
            return questions;
        } catch (Exception e) {
            log.error("Failed to generate quiz questions for avatar {}", avatarId, e);
            return List.of();
        }
    }
}
