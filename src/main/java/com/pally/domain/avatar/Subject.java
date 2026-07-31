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
     * zh label — same values as pally's {@code label_localizer.dart}'s
     * {@code localizedSubject} (copied verbatim, not re-translated, so the two
     * never drift). Used ONLY where a subject label is baked into stored data at
     * write time (e.g. a default avatar name) rather than resolved at client
     * render time via the ARB resolver — most subject display goes through the
     * client resolver instead; this exists for the write-time exception.
     */
    public String labelZh() {
        return switch (this) {
            case MATHS                -> "数学";
            case SCIENCE              -> "科学";
            case ENGLISH              -> "英文";
            case HISTORY              -> "历史";
            case CODING               -> "编程";
            case ART                  -> "美术";
            case GEOGRAPHY            -> "地理";
            case LANGUAGES            -> "语言";
            case MUSIC                -> "音乐";
            case PHYSICAL_EDUCATION   -> "体育";
            case HEALTH               -> "健康";
            case LITERATURE           -> "文学";
            case GENERAL              -> "综合";
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
