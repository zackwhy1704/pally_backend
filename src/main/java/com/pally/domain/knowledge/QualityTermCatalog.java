package com.pally.domain.knowledge;

import com.pally.domain.avatar.Subject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Subject → anchor-term catalogue used by {@link WikiQualityVerifier} for the
 * subject-match density check. Each subject maps to 3–5 high-signal terms; a
 * substantial page (≥ 200 words) that contains NONE of its subject's anchor
 * terms is likely off-topic and gets a quality penalty.
 *
 * <p>Matching is case-insensitive substring (so "calculate"/"calculation" and
 * "react"/"reaction" both hit), which keeps the catalogue tiny while still
 * catching morphological variants.
 */
public final class QualityTermCatalog {

    private QualityTermCatalog() {}

    private static final Map<Subject, List<String>> ANCHORS = Map.ofEntries(
            Map.entry(Subject.MATHS, List.of(
                    "equation", "calculate", "formula", "value", "solve")),
            Map.entry(Subject.SCIENCE, List.of(
                    "hypothesis", "react", "cell", "energy", "organism")),
            Map.entry(Subject.ENGLISH, List.of(
                    "sentence", "grammar", "verb", "noun", "paragraph")),
            Map.entry(Subject.HISTORY, List.of(
                    "century", "war", "empire", "revolution", "ancient")),
            Map.entry(Subject.CODING, List.of(
                    "function", "variable", "loop", "code", "program")),
            Map.entry(Subject.ART, List.of(
                    "colour", "paint", "draw", "shape", "design")),
            Map.entry(Subject.GEOGRAPHY, List.of(
                    "climate", "river", "mountain", "country", "map")),
            Map.entry(Subject.LANGUAGES, List.of(
                    "word", "phrase", "translate", "language", "verb")),
            Map.entry(Subject.MUSIC, List.of(
                    "note", "rhythm", "melody", "chord", "scale")),
            Map.entry(Subject.PHYSICAL_EDUCATION, List.of(
                    "muscle", "exercise", "sport", "fitness", "movement")),
            Map.entry(Subject.HEALTH, List.of(
                    "body", "nutrition", "disease", "hygiene", "wellbeing")),
            Map.entry(Subject.LITERATURE, List.of(
                    "character", "theme", "poem", "novel", "author")),
            // GENERAL has no subject focus — never penalise it.
            Map.entry(Subject.GENERAL, List.of()));

    /**
     * Returns the anchor terms for the given subject name (case-insensitive).
     * Tolerates the legacy alias "MATH" for MATHS. Unknown / unparseable
     * subjects return an empty list (no density penalty).
     */
    public static List<String> anchorsFor(String subjectName) {
        if (subjectName == null || subjectName.isBlank()) return List.of();
        String normalised = subjectName.trim().toUpperCase(Locale.ROOT);
        if ("MATH".equals(normalised)) normalised = "MATHS";
        try {
            return ANCHORS.getOrDefault(Subject.valueOf(normalised), List.of());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }
}
