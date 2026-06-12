package com.pally.domain.avatar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The class "uniform" appearance is derived purely server-side and must be
 * deterministic: the same class id, subject, and name always yield the same
 * band colour, glyph, and initials. Clients depend on this stability — a
 * flickering band colour or initials would look like a bug to centres.
 */
class ClassAvatarAppearanceTest {

    @Test
    void derive_isDeterministic_forSameInputs() {
        ClassAvatarAppearance a = ClassAvatarAppearance.derive("class-abc", Subject.MATHS, "Sec 3 E-Math");
        ClassAvatarAppearance b = ClassAvatarAppearance.derive("class-abc", Subject.MATHS, "Sec 3 E-Math");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void bandColor_isAlwaysFromThePalette() {
        for (int i = 0; i < 50; i++) {
            String hex = ClassAvatarAppearance.bandColor("class-" + i);
            assertThat(ClassAvatarAppearance.PALETTE).contains(hex);
        }
    }

    @Test
    void bandColor_isStableForOneClassId() {
        String first = ClassAvatarAppearance.bandColor("class-stable-id");
        for (int i = 0; i < 20; i++) {
            assertThat(ClassAvatarAppearance.bandColor("class-stable-id")).isEqualTo(first);
        }
    }

    @Test
    void glyphFor_mapsEverySubjectToANonBlankKey() {
        for (Subject s : Subject.values()) {
            assertThat(ClassAvatarAppearance.glyphFor(s)).isNotBlank();
        }
        assertThat(ClassAvatarAppearance.glyphFor(Subject.MATHS)).isEqualTo("math");
        assertThat(ClassAvatarAppearance.glyphFor(Subject.SCIENCE)).isEqualTo("science");
        assertThat(ClassAvatarAppearance.glyphFor(Subject.PHYSICAL_EDUCATION)).isEqualTo("pe");
        assertThat(ClassAvatarAppearance.glyphFor(null)).isEqualTo("general");
    }

    @Test
    void initials_skipFillerAndNumbers_takeFirstTwoSignificantWords() {
        // "Sec" + "E-Math" → S, E (filler "3" number skipped).
        assertThat(ClassAvatarAppearance.initialsFrom("Sec 3 E-Math")).isEqualTo("SE");
        // "Primary" + "Science" → PS ("the"/"class" filler skipped).
        assertThat(ClassAvatarAppearance.initialsFrom("The Primary Science Class")).isEqualTo("PS");
        // Single significant word → first two of its letters.
        assertThat(ClassAvatarAppearance.initialsFrom("Algebra")).isEqualTo("AL");
    }

    @Test
    void initials_handleBlankAndNumericOnly_withFallback() {
        assertThat(ClassAvatarAppearance.initialsFrom(null)).isEqualTo("??");
        assertThat(ClassAvatarAppearance.initialsFrom("   ")).isEqualTo("??");
        assertThat(ClassAvatarAppearance.initialsFrom("404")).isEqualTo("??");
    }

    @Test
    void initials_areUppercased() {
        assertThat(ClassAvatarAppearance.initialsFrom("biology genetics")).isEqualTo("BG");
    }
}
