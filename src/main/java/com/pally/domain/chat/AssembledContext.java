package com.pally.domain.chat;

import java.util.List;
import java.util.Map;

/**
 * Result of ClaudeContextAssembler — the system prompt content plus
 * a JSON trace of what was assembled (persisted on the assistant message).
 *
 * <p>{@code systemBlocks} is the structured representation used for prompt
 * caching. {@code systemPrompt} is kept for backward-compatibility with tests.
 */
public record AssembledContext(
        String systemPrompt,
        String harnessTrace,
        List<Map<String, Object>> systemBlocks
) {
    /** Convenience constructor for legacy code that only needs the string prompt. */
    public AssembledContext(String systemPrompt, String harnessTrace) {
        this(systemPrompt, harnessTrace, List.of());
    }
}
