package com.pally.domain.chat;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Classifies an incoming user message to the most relevant wiki page slug.
 * Uses Jaccard similarity on keyword sets — no LLM required for routing.
 */
@Component
public class TopicClassifier {

    private static final double MIN_SIMILARITY = 0.15;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "shall", "should", "may", "might", "can", "could",
            "i", "me", "my", "we", "you", "your", "he", "she", "it",
            "they", "them", "what", "how", "why", "when", "where", "which",
            "this", "that", "and", "or", "but", "in", "on", "at", "to",
            "for", "of", "with", "about", "help", "understand", "explain",
            "tell", "know", "think", "mean", "like", "just", "please"
    );

    /**
     * Returns the slug of the best-matching hint tree for the given message.
     * Returns empty if no tree meets the minimum similarity threshold.
     */
    public Optional<String> classify(String userMessage, List<SocraticHintTree> trees) {
        if (trees.isEmpty() || userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        Set<String> messageTokens = tokenise(userMessage);
        if (messageTokens.isEmpty()) return Optional.empty();

        String bestSlug = null;
        double bestScore = MIN_SIMILARITY;

        for (SocraticHintTree tree : trees) {
            Set<String> treeTokens = new HashSet<>(tree.getTopicKeywords());
            double score = jaccard(messageTokens, treeTokens);
            if (score > bestScore) {
                bestScore = score;
                bestSlug = tree.getWikiSlug();
            }
        }

        return Optional.ofNullable(bestSlug);
    }

    /** Returns true if the message contains keywords suggesting the child is making progress. */
    public boolean detectsKeywordProgress(String message, List<String> expectedKeywords) {
        if (message == null || expectedKeywords == null || expectedKeywords.isEmpty()) return false;
        Set<String> tokens = tokenise(message);
        long matches = expectedKeywords.stream()
                .filter(kw -> tokens.contains(kw.toLowerCase()))
                .count();
        return matches >= Math.max(1, expectedKeywords.size() / 3);
    }

    /** Returns true if the message looks like the child is deflecting ("idk", "just tell me", etc.) */
    public boolean detectsDeflection(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("i don't know") || lower.contains("idk") ||
               lower.contains("just tell me") || lower.contains("give me the answer") ||
               lower.contains("just give") || lower.contains("skip the hints") ||
               lower.contains("i give up");
    }

    private Set<String> tokenise(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2 && !STOP_WORDS.contains(t))
                .collect(Collectors.toSet());
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
