package com.pally.domain.centre.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pally.shared.exception.BusinessException;

import java.util.Set;

/**
 * Per-class Mochi customization the centre web client edits. Stored as a
 * Jackson-serialized JSON string in {@code org_class.mochi_config} (TEXT, see V74).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MochiConfig(
        int body,
        String accessory,
        String aura) {

    public static final int BODY_VARIANT_MIN = 0;
    public static final int BODY_VARIANT_MAX = 11;

    public static final Set<String> ACCESSORIES =
            Set.of("none", "bow", "cap", "glasses", "crown", "headband");
    public static final Set<String> AURAS =
            Set.of("none", "sparkle", "fire", "chill", "electric", "bloom");

    public MochiConfig validated() {
        if (body < BODY_VARIANT_MIN || body > BODY_VARIANT_MAX) {
            throw new BusinessException(
                    "body must be " + BODY_VARIANT_MIN + "–" + BODY_VARIANT_MAX, 400);
        }
        if (accessory == null || !ACCESSORIES.contains(accessory)) {
            throw new BusinessException("accessory must be one of " + ACCESSORIES, 400);
        }
        if (aura == null || !AURAS.contains(aura)) {
            throw new BusinessException("aura must be one of " + AURAS, 400);
        }
        return this;
    }
}
