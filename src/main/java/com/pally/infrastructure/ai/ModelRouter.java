package com.pally.infrastructure.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ModelRouter {

    @Value("${claude.api.model}")
    private String haiku;

    @Value("${claude.api.sonnet-model:claude-sonnet-4-6}")
    private String sonnet;

    @jakarta.annotation.PostConstruct
    void validate() {
        log.info("[ModelRouter] Haiku model: {}", haiku);
        log.info("[ModelRouter] Sonnet model: {}", sonnet);
        if (sonnet == null || sonnet.isBlank()) {
            log.warn("[ModelRouter] sonnet-model is blank — falling back to Haiku for all tasks");
            sonnet = haiku;
        }
    }

    public String forChat(String userMessage) {
        if (isComplexQuestion(userMessage)) {
            log.info("[ModelRouter] Escalating to SONNET for complex chat: \"{}\"",
                    userMessage.substring(0, Math.min(80, userMessage.length())));
            return sonnet;
        }
        return haiku;
    }

    public String forWikiCompile() {
        return sonnet;
    }

    public String forQuizGeneration() {
        return sonnet;
    }

    public String forPhotoQuestion() {
        return sonnet;
    }

    public String forRelevanceCheck() {
        return haiku;
    }

    public String forCacheKeepalive() {
        return haiku;
    }

    public String getHaikuModel() { return haiku; }

    public String getSonnetModel() { return sonnet; }

    private boolean isComplexQuestion(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase().trim();

        if (lower.length() > 200) return true;

        String[] complexKeywords = {
                "explain why", "explain how", "prove that", "prove this",
                "compare and contrast", "what's the difference between",
                "what is the difference between",
                "step by step", "show your work", "show me how",
                "how does", "how do", "how would",
                "derive", "analyze", "analyse", "evaluate",
                "calculate", "solve for", "find the value",
                "what would happen if", "what if",
                "in what way", "to what extent",
                "critically", "justify", "argue",
        };
        for (String keyword : complexKeywords) {
            if (lower.contains(keyword)) return true;
        }

        if (lower.startsWith("why ")) return true;

        if (lower.matches(".*\\d+\\s*[+\\-*/×÷=]\\s*\\d+.*")) return true;

        return false;
    }
}
