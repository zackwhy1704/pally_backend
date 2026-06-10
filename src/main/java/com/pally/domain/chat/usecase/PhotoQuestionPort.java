package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.WikiPage;

import java.util.List;

public interface PhotoQuestionPort {

    /**
     * Solves photo questions using text-only input (legacy path).
     */
    List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages, List<String> questions);

    /**
     * Solves photo questions with optional direct image input for vision-capable models.
     * When {@code imageBytes} is non-null, the model can SEE the original image
     * alongside the OCR text, dramatically improving accuracy for STEM content.
     *
     * @param avatar      the avatar context
     * @param wikiPages   knowledge base pages for context
     * @param questions   OCR-extracted question text (always provided as fallback)
     * @param imageBytes  raw image bytes (nullable — null means text-only)
     * @param mimeType    image MIME type (nullable — ignored when imageBytes is null)
     */
    default List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages,
                                                    List<String> questions,
                                                    byte[] imageBytes, String mimeType) {
        // Default: ignore image, use text-only path for backward compatibility
        return solveQuestions(avatar, wikiPages, questions);
    }
}
