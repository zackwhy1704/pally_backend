package com.pally.domain.chat;

import com.pally.domain.chat.dto.ChatMessageResponse;
import com.pally.domain.chat.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link ChatMessage} domain objects to API response DTOs.
 */
@Component
public class ChatMapper {

    /**
     * Maps a single {@link ChatMessage} to a {@link ChatMessageResponse}.
     *
     * <p>The {@code avatarId} field is now included in the response so the Flutter client
     * can parse history without needing the URL path parameter. The old {@code sourceFile}
     * single-string field is replaced by {@code sources: List<String>} to match the
     * Flutter {@code ChatMessage.sources} field name and type.
     *
     * @param message domain chat message
     * @return response DTO
     */
    public ChatMessageResponse toResponse(ChatMessage message) {
        List<String> sources = (message.getSourceFile() != null && !message.getSourceFile().isBlank())
                ? List.of(message.getSourceFile())
                : List.of();

        return new ChatMessageResponse(
                message.getId(),
                message.getAvatarId(),
                message.getRole(),
                message.getContent() != null ? message.getContent() : "",
                sources,
                message.getCreatedAt()
        );
    }

    /**
     * Maps a list of {@link ChatMessage} objects to response DTOs.
     *
     * @param messages list of domain chat messages
     * @return list of response DTOs
     */
    public List<ChatMessageResponse> toResponseList(List<ChatMessage> messages) {
        return messages.stream().map(this::toResponse).toList();
    }
}
