package com.pally.infrastructure.persistence.avatar;

import com.pally.domain.avatar.CharacterType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists {@link CharacterType} as its name, but degrades gracefully on read:
 * any unknown/removed value (e.g. a stray "ATWSAKURA" row left over from the
 * unreleased Around-the-World set) maps back to {@link CharacterType#MOCHI}
 * instead of throwing, so a single bad row can never 500 an avatar read.
 */
@Converter(autoApply = false)
@Slf4j
public class CharacterTypeConverter implements AttributeConverter<CharacterType, String> {

    @Override
    public String convertToDatabaseColumn(CharacterType attribute) {
        return attribute == null ? CharacterType.MOCHI.name() : attribute.name();
    }

    @Override
    public CharacterType convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return CharacterType.MOCHI;
        try {
            return CharacterType.valueOf(dbData.trim());
        } catch (IllegalArgumentException e) {
            log.warn("[Avatar] unknown/removed character_type '{}' — defaulting to MOCHI", dbData);
            return CharacterType.MOCHI;
        }
    }
}
