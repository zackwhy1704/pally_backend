package com.pally.api.chat;

import com.pally.api.chat.dto.ChatMessageResponse;
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
     * @param message domain chat message
     * @return response DTO
     */
    public ChatMessageResponse toResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getSourceFile(),
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
