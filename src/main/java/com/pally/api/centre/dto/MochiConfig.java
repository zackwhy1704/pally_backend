package com.pally.api.centre.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pally.shared.exception.BusinessException;

import java.util.Set;

/**
 * Rich per-class Mochi customization the centre web client edits. Stored as a
 * Jackson-serialized JSON string in {@code org_class.mochi_config} (TEXT, see V74).
 * ADDITIVE on top of V59's branded-Mochi fields and the derived
 * {@code ClassAvatarAppearance}; it does not replace them.
 *
 * @param bodyVariant body palette/shape variant, 0–11 (one per character family)
 * @param cheekVariant cheek blush variant, 0–6
 * @param eyeStyle     one of {@link #EYE_STYLES}
 * @param accessory    one of {@link #ACCESSORIES}
 * @param aura         one of {@link #AURAS}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MochiConfig(
        int bodyVariant,
        int cheekVariant,
        String eyeStyle,
        String accessory,
        String aura) {

    public static final int BODY_VARIANT_MIN = 0;
    public static final int BODY_VARIANT_MAX = 11;
    public static final int CHEEK_VARIANT_MIN = 0;
    public static final int CHEEK_VARIANT_MAX = 6;

    public static final Set<String> EYE_STYLES =
            Set.of("happy", "sleepy", "star", "surprised", "uwu");
    public static final Set<String> ACCESSORIES =
            Set.of("none", "bow", "cap", "glasses", "crown", "headband");
    public static final Set<String> AURAS =
            Set.of("none", "sparkle", "fire", "chill", "electric", "bloom");

    /**
     * Validates this config, throwing {@link BusinessException} (HTTP 400) on any
     * out-of-range or unknown enum value. Returns {@code this} for chaining.
     */
    public MochiConfig validated() {
        if (bodyVariant < BODY_VARIANT_MIN || bodyVariant > BODY_VARIANT_MAX) {
            throw new BusinessException(
                    "bodyVariant must be " + BODY_VARIANT_MIN + "–" + BODY_VARIANT_MAX, 400);
        }
        if (cheekVariant < CHEEK_VARIANT_MIN || cheekVariant > CHEEK_VARIANT_MAX) {
            throw new BusinessException(
                    "cheekVariant must be " + CHEEK_VARIANT_MIN + "–" + CHEEK_VARIANT_MAX, 400);
        }
        if (eyeStyle == null || !EYE_STYLES.contains(eyeStyle)) {
            throw new BusinessException("eyeStyle must be one of " + EYE_STYLES, 400);
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
