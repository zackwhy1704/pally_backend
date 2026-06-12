package com.pally.domain.avatar;

/**
 * Visual character types available for an avatar — v2 roster.
 */
public enum CharacterType {
    MOCHI,
    ZAP,
    FINN,
    BOBA,
    PUDDI,
    BYTE,
    NORI,
    CHIMI,
    LUMIS,
    PENCIL,
    SCIENCE,
    PE,
    ART,
    LUNCHBOX,
    LIBRARY,
    HEADMASTER,
    GOLDSTAR
    // Note: the 8 "Around the World" centre characters (ATWBERET, ATWGLOBERIDER,
    // ATWKEBAYA, ATWLIONCITY, ATWPHARAOH, ATWSAKURA, ATWSOMBRERO, ATWKILT) were
    // never released and have been removed. The CharacterTypeConverter maps any
    // stray persisted value (incl. these) back to MOCHI so reads never 500.
}
