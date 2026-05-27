package com.pally.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
) {
    @JsonCreator
    public SyncMessageDto(
            @JsonProperty("id") String id,
            @JsonProperty("role") String roleStr,
            @JsonProperty("content") String content,
            @JsonProperty("messageType") String messageType,
            @JsonProperty("sourceWikiSlug") String sourceWikiSlug,
            @JsonProperty("feedbackType") String feedbackType,
            @JsonProperty("savedToBrain") boolean savedToBrain,
            @JsonProperty("isPhotoMessage") boolean isPhotoMessage,
            @JsonProperty("createdAt") Instant createdAt
    ) {
        this(id, parseRole(roleStr), content, messageType,
                sourceWikiSlug, feedbackType, savedToBrain, isPhotoMessage, createdAt);
    }

    private static ChatMessage.Role parseRole(String s) {
        if (s == null) return ChatMessage.Role.USER;
        return switch (s.toUpperCase()) {
            case "USER" -> ChatMessage.Role.USER;
            case "ASSISTANT", "TUTOR" -> ChatMessage.Role.ASSISTANT;
            default -> ChatMessage.Role.USER;
        };
    }
}
