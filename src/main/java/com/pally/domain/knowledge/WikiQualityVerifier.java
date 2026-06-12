package com.pally.domain.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-compile quality verifier for wiki pages. Uses heuristics (no LLM call)
 * to flag obviously broken pages: too short, garbled, or containing verifiable
 * math errors.
 */
@Component
public class WikiQualityVerifier {

    private static final Logger log = LoggerFactory.getLogger(WikiQualityVerifier.class);

    /**
     * Simple pattern for equations like "2 + 3 = 5" or "10 - 4 = 6".
     * Captures: group(1)=left operand, group(2)=operator, group(3)=right operand, group(4)=result.
     */
    private static final Pattern SIMPLE_EQUATION = Pattern.compile(
            "(\\d+)\\s*([+\\-*/x])\\s*(\\d+)\\s*=\\s*(\\d+)");

    /// Sentence splitter on terminal punctuation. Crude but enough to spot
    /// a page that is mostly stub fragments rather than real prose.
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?]+");

    /// A word is a "fragment sentence" when it has fewer than this many words.
    private static final int FRAGMENT_MAX_WORDS = 5;
    /// Coherence penalty fires when more than this fraction of sentences are fragments.
    private static final double FRAGMENT_FRACTION_LIMIT = 0.40;
    /// Subject-density + repetition checks only apply to substantial pages.
    private static final int DENSITY_MIN_WORDS = 200;
    /// Repetition penalty fires when the top-3 non-stopword tokens are more than
    /// this fraction of all word tokens (a tell-tale of OCR/compile artefacts).
    private static final double REPETITION_LIMIT = 0.60;

    /// Tiny stopword set so the repetition check counts content words, not "the".
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at",
            "is", "are", "was", "were", "be", "been", "it", "its", "this", "that",
            "these", "those", "for", "with", "as", "by", "from", "they", "you",
            "we", "i", "he", "she", "his", "her", "their", "our", "your");

    public record VerificationResult(String pageSlug, double qualityScore, List<String> issues) {}

    /**
     * Verify a compiled wiki page for basic quality.
     *
     * @param page    the wiki page to verify
     * @param subject the avatar's subject (e.g. "MATHS", "SCIENCE")
     * @return verification result with score and list of issues
     */
    public VerificationResult verify(WikiPage page, String subject) {
        List<String> issues = new ArrayList<>();
        double score = 1.0;

        String content = page.getContent();
        if (content == null || content.isBlank()) {
            issues.add("Page has no content.");
            return new VerificationResult(page.getSlug(), 0.0, issues);
        }

        // 1. Length check
        String[] words = content.strip().split("\\s+");
        if (words.length < 50) {
            score -= 0.3;
            issues.add("Very short page (" + words.length + " words).");
        }

        // 2. For MATH/SCIENCE subjects: verify simple arithmetic equations
        if ("MATHS".equalsIgnoreCase(subject) || "MATH".equalsIgnoreCase(subject)
                || "SCIENCE".equalsIgnoreCase(subject)) {
            List<String> mathErrors = verifySimpleEquations(content);
            if (!mathErrors.isEmpty()) {
                score -= 0.1 * mathErrors.size();
                issues.addAll(mathErrors);
            }
        }

        // 3. Coherence: a page that is mostly short fragments (not real
        //    sentences) reads like stub bullet-debris, not learnable prose.
        if (isMostlyFragments(content)) {
            score -= 0.2;
            issues.add("Low coherence: most sentences are very short fragments.");
        }

        // 4. Subject-match density: a substantial page that contains NONE of
        //    its subject's anchor terms is probably off-topic for this tutor.
        if (words.length >= DENSITY_MIN_WORDS && lacksSubjectAnchors(content, subject)) {
            score -= 0.2;
            issues.add("Off-topic: no " + subject + " subject terms found in a long page.");
        }

        // 5. Repetition: a few tokens dominating the whole page signals an
        //    OCR/compile artefact (e.g. a repeated header or garbled loop).
        if (words.length >= DENSITY_MIN_WORDS && isHighlyRepetitive(words)) {
            score -= 0.3;
            issues.add("Highly repetitive: a few words make up most of the page.");
        }

        score = Math.max(0.0, Math.min(1.0, score));

        log.info("[WikiQuality] page={} score={} issues={}", page.getSlug(),
                String.format("%.2f", score), issues.size());
        return new VerificationResult(page.getSlug(), score, issues);
    }

    /**
     * Finds simple arithmetic equations in the text and verifies them.
     */
    List<String> verifySimpleEquations(String text) {
        List<String> errors = new ArrayList<>();
        Matcher matcher = SIMPLE_EQUATION.matcher(text);

        while (matcher.find()) {
            try {
                long left = Long.parseLong(matcher.group(1));
                String op = matcher.group(2);
                long right = Long.parseLong(matcher.group(3));
                long stated = Long.parseLong(matcher.group(4));

                long expected = switch (op) {
                    case "+" -> left + right;
                    case "-" -> left - right;
                    case "*", "x" -> left * right;
                    case "/" -> right != 0 ? left / right : stated; // skip div-by-zero
                    default -> stated; // unknown op, skip
                };

                if (expected != stated) {
                    errors.add("Possible math error: " + left + " " + op + " " + right
                            + " = " + stated + " (expected " + expected + ").");
                }
            } catch (NumberFormatException e) {
                // operands too large for long — skip
            }
        }
        return errors;
    }

    /**
     * True when more than {@link #FRAGMENT_FRACTION_LIMIT} of the page's
     * sentences are fragments ({@code < FRAGMENT_MAX_WORDS} words). Requires at
     * least two sentences so a single-line page isn't unfairly flagged.
     */
    boolean isMostlyFragments(String content) {
        String[] sentences = SENTENCE_SPLIT.split(content);
        int total = 0;
        int fragments = 0;
        for (String s : sentences) {
            String trimmed = s.strip();
            if (trimmed.isEmpty()) continue;
            total++;
            if (trimmed.split("\\s+").length < FRAGMENT_MAX_WORDS) fragments++;
        }
        if (total < 2) return false;
        return (double) fragments / total > FRAGMENT_FRACTION_LIMIT;
    }

    /**
     * True when the content contains NONE of the subject's anchor terms
     * (case-insensitive substring). Subjects with no anchors (GENERAL, unknown)
     * never lack anchors, so they're never penalised.
     */
    boolean lacksSubjectAnchors(String content, String subject) {
        List<String> anchors = QualityTermCatalog.anchorsFor(subject);
        if (anchors.isEmpty()) return false;
        String haystack = content.toLowerCase(Locale.ROOT);
        return anchors.stream().noneMatch(haystack::contains);
    }

    /**
     * True when the top-3 most frequent non-stopword tokens make up more than
     * {@link #REPETITION_LIMIT} of all word tokens.
     */
    boolean isHighlyRepetitive(String[] words) {
        Map<String, Integer> freq = new HashMap<>();
        int totalTokens = 0;
        for (String w : words) {
            String token = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (token.isEmpty() || STOPWORDS.contains(token)) continue;
            totalTokens++;
            freq.merge(token, 1, Integer::sum);
        }
        if (totalTokens == 0) return false;
        int top3 = freq.values().stream()
                .sorted((x, y) -> y - x)
                .limit(3)
                .mapToInt(Integer::intValue)
                .sum();
        return (double) top3 / totalTokens > REPETITION_LIMIT;
    }
}
