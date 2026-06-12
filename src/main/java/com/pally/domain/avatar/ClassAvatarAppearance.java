package com.pally.domain.avatar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Server-derived "class uniform" appearance for a {@link AvatarKind#CENTRE_CLASS}
 * avatar. Zero config, zero per-class art: the band colour, subject glyph, and
 * initials are computed deterministically from the class id, subject, and name.
 *
 * <p>Clients render this verbatim — they map {@link #subjectGlyph()} to an icon
 * and paint the {@link #bandColorHex()} band with the {@link #initials()} on top.
 * Derivation lives ONLY here; clients never recompute it.
 *
 * @param bandColorHex a brand-safe band colour, chosen from a fixed 12-colour
 *                     palette by {@code floorMod(classId.hashCode(), 12)}
 * @param subjectGlyph a stable glyph key ("math", "science", "english", …) the
 *                     client maps to an icon
 * @param initials     1–2 uppercase letters from the significant words of the
 *                     class name (e.g. "Sec 3 E-Math" → "EM")
 */
public record ClassAvatarAppearance(
        String bandColorHex,
        String subjectGlyph,
        String initials
) {

    /**
     * Fixed 12-colour brand-safe palette. Indexed by a hash of the class id so a
     * class always gets the same band colour, and the spread across classes is
     * even. Order is stable — never reorder, or existing classes change colour.
     */
    public static final List<String> PALETTE = List.of(
            "#7042ED", // purple
            "#00BBA4", // teal
            "#FF6660", // coral
            "#FFB81A", // amber
            "#2EC870", // green
            "#FF6BAE", // pink
            "#3B82F6", // blue
            "#F97316", // orange
            "#8B5CF6", // violet
            "#14B8A6", // cyan-teal
            "#EF4444", // red
            "#EAB308"  // gold
    );

    private static final Set<String> FILLER_WORDS = Set.of(
            "the", "a", "an", "of", "and", "for", "to", "in", "class", "group", "level");

    /**
     * Derives the uniform appearance for a class avatar.
     *
     * @param classId   the class id (drives the band colour; never null/blank)
     * @param subject   the class subject (drives the glyph; may be null → "general")
     * @param className the class display name (drives the initials)
     * @return a fully populated, deterministic appearance
     */
    public static ClassAvatarAppearance derive(String classId, Subject subject, String className) {
        return new ClassAvatarAppearance(
                bandColor(classId),
                glyphFor(subject),
                initialsFrom(className));
    }

    static String bandColor(String classId) {
        String key = classId == null ? "" : classId;
        int idx = Math.floorMod(key.hashCode(), PALETTE.size());
        return PALETTE.get(idx);
    }

    static String glyphFor(Subject subject) {
        if (subject == null) return "general";
        return switch (subject) {
            case MATHS              -> "math";
            case SCIENCE            -> "science";
            case ENGLISH            -> "english";
            case HISTORY            -> "history";
            case CODING             -> "coding";
            case ART                -> "art";
            case GEOGRAPHY          -> "geography";
            case LANGUAGES          -> "languages";
            case MUSIC              -> "music";
            case PHYSICAL_EDUCATION -> "pe";
            case HEALTH             -> "health";
            case LITERATURE         -> "literature";
            case GENERAL            -> "general";
        };
    }

    /**
     * First letters of up to 2 significant words of the class name, upper-cased.
     * Filler words ("the", "class", …) and pure-number tokens ("3", "4") are
     * skipped. Hyphenated tokens count by their first alphabetic letter, so
     * "E-Math" contributes "E". Falls back to the first 1–2 letters of the raw
     * name when no significant words are found.
     */
    static String initialsFrom(String className) {
        if (className == null || className.isBlank()) return "??";
        String[] tokens = className.trim().split("[\\s/]+");
        List<Character> letters = new ArrayList<>();
        for (String token : tokens) {
            if (letters.size() >= 2) break;
            String cleaned = token.toLowerCase();
            // Strip leading non-letters so "E-Math" → starts at 'e'.
            if (FILLER_WORDS.contains(cleaned)) continue;
            Character first = firstLetter(token);
            if (first == null) continue; // pure number / symbol token
            letters.add(Character.toUpperCase(first));
        }
        if (letters.isEmpty()) {
            // No significant word — fall back to first letters of the raw string.
            String stripped = className.replaceAll("[^A-Za-z]", "");
            if (stripped.isEmpty()) return "??";
            return stripped.substring(0, Math.min(2, stripped.length())).toUpperCase();
        }
        if (letters.size() == 1) {
            // Exactly one significant word — use its first two letters, like a
            // single-name initial ("Algebra" → "AL"), so we never show a lone
            // letter on the uniform.
            String word = firstSignificantWord(tokens);
            if (word != null && word.length() >= 2) {
                return word.substring(0, 2).toUpperCase();
            }
            return String.valueOf(letters.get(0));
        }
        StringBuilder sb = new StringBuilder();
        for (Character c : letters) sb.append(c);
        return sb.toString();
    }

    /** The alphabetic letters of the first significant, letter-bearing token. */
    private static String firstSignificantWord(String[] tokens) {
        for (String token : tokens) {
            if (FILLER_WORDS.contains(token.toLowerCase())) continue;
            String letters = token.replaceAll("[^A-Za-z]", "");
            if (!letters.isEmpty()) return letters;
        }
        return null;
    }

    private static Character firstLetter(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) return token.charAt(i);
        }
        return null;
    }
}
