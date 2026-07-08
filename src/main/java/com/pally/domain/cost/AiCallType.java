package com.pally.domain.cost;

/** What an AI call was FOR — so cost maps by feature, not just by model. */
public enum AiCallType {
    COMPILE,           // wiki compile (Gemini or Haiku fallback) — the expensive op
    CHAT,              // tutor chat
    RELEVANCE,         // upload relevance check
    MARKING,           // marking-standard compile / homework-feedback draft
    WEAKNESS_REBUILD,  // the per-mastery weakness-profile recompile
    OTHER;

    /** Coarse category for a fine task label (the purpose_label). Keeps old and
     *  new rows queryable by the same call_type buckets. */
    public static AiCallType fromLabel(String label) {
        String t = label == null ? "" : label.toLowerCase();
        if (t.contains("relevance")) return RELEVANCE;
        if (t.contains("chat")) return CHAT;
        if (t.contains("mark") || t.contains("homework") || t.contains("feedback")) return MARKING;
        if (t.contains("compile") || t.contains("wiki") || t.contains("module")
                || t.contains("prove") || t.contains("teach")) return COMPILE;
        return OTHER;
    }
}
