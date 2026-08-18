package com.pally.domain.syllabus;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spot-checks a piece of AI-generated syllabus-pack content against the OER source text
 * it was grounded on, so a reviewer (or a CI gate) can catch near-verbatim reproduction
 * before a pack is ever published. NOT a moderation gate by itself — a real syllabus_content_pack
 * still only becomes servable through {@code SyllabusContentPackService#approveItems}; this is
 * an additional originality signal fed into that human review.
 *
 * <p>Method: 8-word shingle (n-gram) Jaccard similarity — cheap, dependency-free, and far
 * more sensitive to lifted PHRASES than a whole-string diff, which near-verbatim copying
 * with light rewording would otherwise dodge.
 */
public final class VerbatimSimilarityChecker {

    private static final int SHINGLE_SIZE = 8;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private VerbatimSimilarityChecker() {
    }

    /** @return Jaccard similarity of the two texts' 8-word shingle sets, in [0.0, 1.0]. */
    public static double similarityRatio(String generated, String source) {
        Set<String> a = shingles(generated);
        Set<String> b = shingles(source);
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /** Flags content whose shingle-overlap with the source exceeds {@code threshold}. */
    public static boolean isSuspiciouslyVerbatim(String generated, String source, double threshold) {
        return similarityRatio(generated, source) >= threshold;
    }

    private static Set<String> shingles(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String[] words = WHITESPACE.split(text.trim().toLowerCase(Locale.ROOT));
        Set<String> out = new HashSet<>();
        if (words.length < SHINGLE_SIZE) {
            out.add(String.join(" ", words));
            return out;
        }
        for (int i = 0; i <= words.length - SHINGLE_SIZE; i++) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + SHINGLE_SIZE)));
        }
        return out;
    }
}
