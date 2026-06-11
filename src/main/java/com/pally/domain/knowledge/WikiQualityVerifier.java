package com.pally.domain.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
}
