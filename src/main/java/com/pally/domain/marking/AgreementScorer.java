package com.pally.domain.marking;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hard-data agreement between an AI feedback draft and the teacher's RELEASED
 * mark — the credibility signal for "is the marking assistant improving?". Pure
 * + deterministic (no I/O): every input is data we already store
 * (aiDraftFeedbackJson vs teacherFeedback / aiGrade vs teacherGrade). Agreement
 * can only rise if the AI genuinely started matching the teacher — unlike a
 * satisfaction rating, which can be gamed.
 */
public final class AgreementScorer {

    private AgreementScorer() {}

    public enum GradeAgreement { EXACT, WITHIN_ONE_BAND, MISMATCH, UNKNOWN }

    /** Marking concepts we can detect by keyword in free-text feedback. */
    private static final List<String> CONCEPTS = List.of(
            "method", "accuracy", "ecf", "units", "working", "substitution",
            "formula", "rounding", "significant figures", "structure", "explanation");

    /**
     * Grade agreement on a common 0..N band scale. EXACT = same band,
     * WITHIN_ONE_BAND = off by one, MISMATCH = further apart, UNKNOWN = a grade
     * couldn't be parsed.
     */
    public static GradeAgreement gradeAgreement(String aiGrade, String teacherGrade) {
        Double a = toBand(aiGrade);
        Double t = toBand(teacherGrade);
        if (a == null || t == null) return GradeAgreement.UNKNOWN;
        double d = Math.abs(a - t);
        if (d < 0.5) return GradeAgreement.EXACT;
        if (d <= 1.0 + 1e-9) return GradeAgreement.WITHIN_ONE_BAND;
        return GradeAgreement.MISMATCH;
    }

    /**
     * Parse a grade to a numeric band: "3/5"→3, "75%"→~3.75/5-scaled, letters
     * A..F→5..0, plain numbers as-is. Returns null when unparseable.
     */
    static Double toBand(String grade) {
        if (grade == null) return null;
        String g = grade.trim().toUpperCase();
        if (g.isEmpty()) return null;
        // "N/M" fraction → scale to /5 for a common band.
        var frac = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)").matcher(g);
        if (frac.find()) {
            double num = Double.parseDouble(frac.group(1));
            double den = Double.parseDouble(frac.group(2));
            return den == 0 ? null : (num / den) * 5.0;
        }
        // Percentage → /5 band.
        var pct = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(g);
        if (pct.find()) return (Double.parseDouble(pct.group(1)) / 100.0) * 5.0;
        // Letter grade (optionally with +/-) → 5..0, with +/- nudging the band.
        var letter = java.util.regex.Pattern.compile("^([A-F])([+-])?").matcher(g);
        if (letter.find()) {
            double base = switch (letter.group(1)) {
                case "A" -> 5; case "B" -> 4; case "C" -> 3;
                case "D" -> 2; case "E" -> 1; default -> 0;
            };
            String pm = letter.group(2);
            if ("+".equals(pm)) base += 0.3;
            else if ("-".equals(pm)) base -= 0.3;
            return base;
        }
        // Plain number.
        var num = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(g);
        if (num.find()) return Double.parseDouble(num.group(1));
        return null;
    }

    /**
     * Token-sequence similarity 0..1 between the AI draft comments and the
     * released teacher comments: 1.0 = kept verbatim, 0 = fully rewritten.
     * Normalised token-level Levenshtein ratio.
     */
    public static double commentSimilarity(String aiComments, String teacherComments) {
        List<String> a = tokens(aiComments);
        List<String> b = tokens(teacherComments);
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int dist = levenshtein(a, b);
        int max = Math.max(a.size(), b.size());
        return Math.max(0.0, 1.0 - (double) dist / max);
    }

    /** Marking concepts present in the teacher's feedback but NOT the AI draft. */
    public static Set<String> conceptsAddedByTeacher(String aiComments, String teacherComments) {
        String ai = aiComments == null ? "" : aiComments.toLowerCase();
        String te = teacherComments == null ? "" : teacherComments.toLowerCase();
        Set<String> added = new LinkedHashSet<>();
        for (String c : CONCEPTS) {
            if (te.contains(c) && !ai.contains(c)) added.add(c);
        }
        return added;
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static List<String> tokens(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .filter(t -> !t.isBlank()).toList();
    }

    private static int levenshtein(List<String> a, List<String> b) {
        int[] prev = new int[b.size() + 1];
        int[] cur = new int[b.size() + 1];
        for (int j = 0; j <= b.size(); j++) prev[j] = j;
        for (int i = 1; i <= a.size(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.size(); j++) {
                int cost = a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.size()];
    }
}
