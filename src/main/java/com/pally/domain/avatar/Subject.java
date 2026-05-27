package com.pally.domain.avatar;

/**
 * Academic subjects an avatar can specialise in — v2 roster.
 */
public enum Subject {
    MATHS,
    SCIENCE,
    ENGLISH,
    HISTORY,
    CODING,
    ART,
    GEOGRAPHY,
    LANGUAGES,
    MUSIC,
    PHYSICAL_EDUCATION,
    HEALTH,
    LITERATURE,
    GENERAL;

    public String label() {
        return switch (this) {
            case MATHS                -> "Maths";
            case SCIENCE              -> "Science";
            case ENGLISH              -> "English";
            case HISTORY              -> "History";
            case CODING               -> "Coding";
            case ART                  -> "Art";
            case GEOGRAPHY            -> "Geography";
            case LANGUAGES            -> "Languages";
            case MUSIC                -> "Music";
            case PHYSICAL_EDUCATION   -> "Physical Education";
            case HEALTH               -> "Health";
            case LITERATURE           -> "Literature";
            case GENERAL              -> "General";
        };
    }
}
