package com.pally.api.chat.dto;

import com.pally.domain.chat.ChatMessage;

import java.time.Instant;

/**
 * Response body for a single chat message.
 *
 * @param id         unique message identifier
 * @param role       message author role (USER or ASSISTANT)
 * @param content    message text content
 * @param sourceFile optional source file reference (for assistant messages)
 * @param createdAt  message timestamp
 */
public record ChatMessageResponse(
        String id,
        ChatMessage.Role role,
        String content,
        String sourceFile,
        Instant createdAt
) {}
