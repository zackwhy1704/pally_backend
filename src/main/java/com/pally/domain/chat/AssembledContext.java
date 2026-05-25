package com.pally.domain.chat;

/**
 * Result of ClaudeContextAssembler — the system prompt to send to Claude
 * plus a JSON trace of what was assembled (persisted on the assistant message).
 */
public record AssembledContext(String systemPrompt, String harnessTrace) {}
