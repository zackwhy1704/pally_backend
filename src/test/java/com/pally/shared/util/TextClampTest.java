package com.pally.shared.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact failure that intermittently 400'd multi-doc compiles: a raw
 * String.substring at a length boundary can split a UTF-16 surrogate pair, leaving
 * an unpaired surrogate that is invalid UTF-8 and fails the Postgres write. The
 * code-point-safe clamp must never produce one, and must respect the COLUMN's
 * character count (not UTF-16 units).
 */
class TextClampTest {

    @Test
    void nullAndShortStringsPassThrough() {
        assertThat(TextClamp.toCodePoints(null, 10)).isNull();
        assertThat(TextClamp.toCodePoints("short", 10)).isEqualTo("short");
        assertThat(TextClamp.toCodePoints("exactly10!", 10)).isEqualTo("exactly10!");
    }

    @Test
    void clampNeverSplitsASurrogatePairAtTheBoundary() {
        // "😀" (U+1F600) is one code point but TWO UTF-16 units. Place 4 of them so a
        // naive substring(0, n) at an odd unit boundary would split the pair.
        String emojis = "😀😀😀😀";           // 4 code points, 8 UTF-16 units
        String clamped = TextClamp.toCodePoints(emojis, 2);

        assertThat(clamped.codePointCount(0, clamped.length())).isEqualTo(2);
        // No unpaired surrogate → round-trips through UTF-8 unchanged.
        byte[] utf8 = clamped.getBytes(StandardCharsets.UTF_8);
        assertThat(new String(utf8, StandardCharsets.UTF_8)).isEqualTo(clamped);
        assertThat(clamped).isEqualTo("😀😀");
    }

    @Test
    void clampCountsCodePointsNotUtf16Units_soItNeverOvershootsTheColumn() {
        // 200 emojis = 200 code points but 400 UTF-16 units. A VARCHAR(160) column
        // counts characters, so the clamp must yield 160 code points, not 160 units.
        String emojis = "😀".repeat(200);
        String clamped = TextClamp.toCodePoints(emojis, 160);
        assertThat(clamped.codePointCount(0, clamped.length())).isEqualTo(160);
    }
}
