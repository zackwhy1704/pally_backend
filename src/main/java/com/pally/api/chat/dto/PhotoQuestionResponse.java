package com.pally.api.chat.dto;

import java.util.List;

/**
 * Response for {@code POST /api/v1/avatars/{id}/photo-question}.
 *
 * <p>{@code levelledUp} + {@code newLevel} let the client fire the
 * level-up celebration whenever this credit pushes the user across a
 * threshold — the quiz path already did this; photo questions now match.
 */
public record PhotoQuestionResponse(
        List<QuestionAnswerDto> answers,
        int xpEarned,
        String sourceWikiPage,
        boolean levelledUp,
        int newLevel
) {}
