package com.pally.domain.knowledge.groundedness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically pulls checkable factual assertions from generated content —
 * NO LLM call. Includes sentences with a hard fact (number/formula/date/named
 * entity) or a definitional verb; excludes pedagogical scaffolding, instructions,
 * and questions. Each kept sentence is tagged {@link Claim#hardFact()} so the
 * verifier can separate fabrication (hard fact not in source → FLAG) from
 * legitimate elaboration (soft definitional/rephrasing → ALLOW).
 *
 * <p>Calibration note: a bare single-concept definition ("Mitochondria is the
 * powerhouse") is intentionally treated as SOFT (not a hard fact), biasing toward
 * precision — we never want to flag legitimate lesson-building. Only numbers,
 * formulas, dates, and multi-word named entities count as hard facts.
 */
public final class ClaimExtractor {

    private ClaimExtractor() {}

    private static final Pattern SENTENCE = Pattern.compile("[^.!?\\n]+[.!?]?");
    private static final Pattern NUMBER = Pattern.compile("\\d");
    // '=' or common math operators / superscripts → a formula or equation.
    private static final Pattern FORMULA = Pattern.compile("[=√×÷±∑∫≈≤≥]|\\^|\\b\\d+\\s*/\\s*\\d+\\b");
    // Two or more consecutive capitalised words → a named entity (weak but precise).
    private static final Pattern NAMED_ENTITY = Pattern.compile("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+\\b");
    private static final Pattern DEFINITIONAL = Pattern.compile(
            "\\b(is|are|means|equals|defined as|refers to|stands for|consists of)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> SCAFFOLD_PREFIXES = List.of(
            "let's", "let us", "now,", "next,", "first,", "finally,",
            "this is important", "we will", "you will learn", "you'll learn",
            "remember", "in this lesson", "for example", "for instance",
            "think of", "imagine", "consider", "note that", "tip:");

    public static List<Claim> extract(String text) {
        List<Claim> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            String s = m.group().trim();
            if (s.length() < 8) continue;            // too short to be a claim
            if (s.endsWith("?")) continue;           // the question itself, not a claim
            String lower = s.toLowerCase();
            if (SCAFFOLD_PREFIXES.stream().anyMatch(lower::startsWith)) continue;

            boolean hardFact = isHardFact(s);
            // Keep substantive sentences only: a hard fact OR a definitional
            // statement. Pure soft elaboration (neither) is presumed fine → skipped.
            if (hardFact || DEFINITIONAL.matcher(s).find()) {
                out.add(new Claim(s, hardFact));
            }
        }
        return out;
    }

    static boolean isHardFact(String s) {
        return NUMBER.matcher(s).find()
                || FORMULA.matcher(s).find()
                || NAMED_ENTITY.matcher(s).find();
    }
}
