package com.pally.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSanitizerTest {

    // Built from (char) casts so the source file itself holds NO raw control bytes.
    private static final String NUL = String.valueOf((char) 0x00);
    private static final String SOH = String.valueOf((char) 0x01);
    private static final String BEL = String.valueOf((char) 0x07);

    @Test
    void removesTheNulByteThatPostgresRejects() {
        // The exact 22021 culprit: a NUL inside extracted text (here between digits
        // and CJK, as PDFBox emitted it for the fixture).
        String out = TextSanitizer.stripUnstorableChars("218" + NUL + "号巴士");
        assertThat(out).isEqualTo("218号巴士");
        assertThat(out.chars().anyMatch(Character::isISOControl)).isFalse();
    }

    @Test
    void dropsOtherControlCharsButKeepsTabNewlineCrAndSpaces() {
        String in = "a" + SOH + "b\tc d\ne\rf" + BEL + "g";
        assertThat(TextSanitizer.stripUnstorableChars(in)).isEqualTo("ab\tc d\ne\rfg");
    }

    @Test
    void preservesOrdinaryTextIncludingSpacesAndCjkUnchanged() {
        String in = "林小峰 住在 the 47th block\n第二行";
        assertThat(TextSanitizer.stripUnstorableChars(in)).isEqualTo(in);
    }

    @Test
    void isNullAndEmptySafe() {
        assertThat(TextSanitizer.stripUnstorableChars(null)).isNull();
        assertThat(TextSanitizer.stripUnstorableChars("")).isEmpty();
    }

    @Test
    void isIdempotent() {
        String once = TextSanitizer.stripUnstorableChars("x" + NUL + "y");
        assertThat(once).isEqualTo("xy");
        assertThat(TextSanitizer.stripUnstorableChars(once)).isEqualTo(once);
    }
}
