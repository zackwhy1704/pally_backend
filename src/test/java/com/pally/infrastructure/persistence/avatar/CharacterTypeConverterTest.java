package com.pally.infrastructure.persistence.avatar;

import com.pally.domain.avatar.CharacterType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The converter is the safety net for removing the unreleased ATW characters:
 * a stray persisted value must degrade to MOCHI on read, never throw.
 */
class CharacterTypeConverterTest {

    private final CharacterTypeConverter converter = new CharacterTypeConverter();

    @Test
    void removedAtwValue_degradesToMochi_insteadOfThrowing() {
        assertThat(converter.convertToEntityAttribute("ATWSAKURA")).isEqualTo(CharacterType.MOCHI);
        assertThat(converter.convertToEntityAttribute("ATWPHARAOH")).isEqualTo(CharacterType.MOCHI);
    }

    @Test
    void unknownGarbage_degradesToMochi() {
        assertThat(converter.convertToEntityAttribute("NOT_A_CHARACTER")).isEqualTo(CharacterType.MOCHI);
        assertThat(converter.convertToEntityAttribute(null)).isEqualTo(CharacterType.MOCHI);
        assertThat(converter.convertToEntityAttribute("")).isEqualTo(CharacterType.MOCHI);
    }

    @Test
    void knownValue_roundTrips() {
        assertThat(converter.convertToEntityAttribute("ZAP")).isEqualTo(CharacterType.ZAP);
        assertThat(converter.convertToDatabaseColumn(CharacterType.ZAP)).isEqualTo("ZAP");
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("MOCHI");
    }
}
