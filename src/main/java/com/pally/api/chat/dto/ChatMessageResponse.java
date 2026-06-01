package com.pally.api.chat.dto;

import com.pally.domain.chat.ChatMessage;

import java.time.Instant;
import java.util.List;

/**
 * Response body for a single chat message.
 *
 * <p>Contract (matches Flutter {@code ChatMessage} freezed model):
 * <ul>
 *   <li>{@code avatarId} — always present; the Flutter parser required it and was crashing on null.</li>
 *   <li>{@code sources}  — {@code List<String>}, empty if no source; replaces the old single
 *       {@code sourceFile} string so the field name matches {@code ChatMessage.sources}.</li>
 * </ul>
 *
 * @param id        unique message identifier
 * @param avatarId  avatar this message belongs to
 * @param role      message author role (USER or ASSISTANT)
 * @param content   message text content
 * @param sources   list of source wiki-page slugs (empty for user messages)
 * @param createdAt message timestamp
 */
public record ChatMessageResponse(
        String id,
        String avatarId,
        ChatMessage.Role role,
        String content,
        List<String> sources,
        Instant createdAt
) {}
