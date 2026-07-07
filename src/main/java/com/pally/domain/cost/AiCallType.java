package com.pally.domain.cost;

/** What an AI call was FOR — so cost maps by feature, not just by model. */
public enum AiCallType {
    COMPILE,           // wiki compile (Gemini or Haiku fallback) — the expensive op
    CHAT,              // tutor chat
    RELEVANCE,         // upload relevance check
    MARKING,           // marking-standard compile / homework-feedback draft
    WEAKNESS_REBUILD,  // the per-mastery weakness-profile recompile
    OTHER
}
