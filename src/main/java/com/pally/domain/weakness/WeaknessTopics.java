package com.pally.domain.weakness;

import java.util.Arrays;
import java.util.stream.Collectors;

/// Render a weak-topic slug into a human-readable label. Shared by BOTH weakness surfaces —
/// the teacher roster view (TeacherWeaknessService.perStudentWeakness) and the student focus
/// view (WeaknessProfileService.focusFor) — so both display the SAME real weak topics from the
/// SAME signal (weakSlugsFor), rather than each formatting its own way (or one reading a
/// different source).
public final class WeaknessTopics {

    private WeaknessTopics() {}

    /** "fractions-division-step" -> "Fractions Division Step". */
    public static String label(String slug) {
        if (slug == null) return "";
        String s = slug.replace('-', ' ').replace('_', ' ').strip();
        if (s.isEmpty()) return slug;
        return Arrays.stream(s.split(" "))
                .filter(w -> !w.isBlank())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }
}
