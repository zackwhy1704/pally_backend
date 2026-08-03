package com.pally.domain.chat;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classifies an incoming user message to the most relevant wiki page slug.
 * Uses Jaccard similarity on keyword sets — no LLM required for routing.
 */
@Component
public class TopicClassifier {

    private static final double MIN_SIMILARITY = 0.15;

    // Relocated from ClaudeContextAssembler — this used to feed a "Socratic
    // unlock" note into a system-prompt block that a later refactor
    // (SendMessageUseCase.buildBlocksWithSocraticTail) silently overwrote
    // before it ever reached the model. Same patterns, same trigger shape —
    // a relocation to where the live escape/deflection decision is actually
    // made, not a redesign.
    private static final List<Pattern> FRUSTRATION_PATTERNS = List.of(
            Pattern.compile("don.{0,4}t understand", Pattern.CASE_INSENSITIVE),
            Pattern.compile("still confused", Pattern.CASE_INSENSITIVE),
            Pattern.compile("tell me", Pattern.CASE_INSENSITIVE),
            Pattern.compile("just give", Pattern.CASE_INSENSITIVE),
            Pattern.compile("what is the answer", Pattern.CASE_INSENSITIVE)
    );
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

    /**
     * Returns true when:
     * <ul>
     *   <li>The current session has ≥ 4 user-turn messages, AND</li>
     *   <li>The last 2 user messages (including the current one) contain at least
     *       one frustration signal keyword.</li>
     * </ul>
     */
    public boolean detectsFrustration(List<ChatMessage> recentHistory, String currentMessage) {
        if (recentHistory == null || recentHistory.isEmpty()) return false;

        List<String> userMessages = recentHistory.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.USER)
                .map(ChatMessage::getContent)
                .filter(c -> c != null && !c.isBlank())
                .toList();

        if (userMessages.size() < 4) return false;

        // Check the last 2 user turns (the most recent and the one before)
        int size = userMessages.size();
        List<String> last2 = new ArrayList<>();
        last2.add(userMessages.get(size - 1));
        if (size >= 2) last2.add(userMessages.get(size - 2));
        // Also include the current message being sent
        if (currentMessage != null && !currentMessage.isBlank()) last2.add(currentMessage);

        for (String msg : last2) {
            for (Pattern p : FRUSTRATION_PATTERNS) {
                if (p.matcher(msg).find()) return true;
            }
        }
        return false;
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
