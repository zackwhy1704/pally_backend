package com.pally.api.chat.dto;

import com.pally.domain.chat.ChatMessage;

import java.time.Instant;

public record SyncMessageDto(
        String id,
        ChatMessage.Role role,
        String content,
        String messageType,
        String sourceWikiSlug,
        String feedbackType,
        boolean savedToBrain,
        boolean isPhotoMessage,
        Instant createdAt
) {}
