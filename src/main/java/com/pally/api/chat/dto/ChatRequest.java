package com.pally.api.chat.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request body for sending a chat message to an avatar.
 *
 * @param message     the user's message text (required, non-blank)
 * @param wikiPageIds optional list of specific wiki page IDs to include as context
 * @param moduleId    optional module ID — when present, chat is grounded to that module's content
 */
public record ChatRequest(
        @NotBlank(message = "Message must not be blank") String message,
        List<String> wikiPageIds,
        String moduleId
) {}
