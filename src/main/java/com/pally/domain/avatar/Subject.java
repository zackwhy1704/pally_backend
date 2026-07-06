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

    /**
     * Whether "is this content on-topic for the subject?" is a coherent question.
     * GENERAL has no single topic, so topic-relevance scoring is meaningless for it —
     * it scores educational-but-off-"topic" content (a sales book, an accounting doc)
     * as irrelevant and false-blocks it. A topically-UNBOUNDED subject bypasses the
     * topic-relevance gate but STILL keeps the study-material floor. Written as a
     * predicate, not {@code == GENERAL}, so a future free-text/unbounded subject
     * degrades correctly (treat unknown/unbounded like GENERAL).
     */
    public boolean isTopicallyBounded() {
        return this != GENERAL;
    }
}
