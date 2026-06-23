package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.Sm2Scheduler;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateFlashcardUseCase {

    private final FlashcardRepository flashcardRepository;
    private final AvatarRepository avatarRepository;

    public FlashCard execute(String cardId, CardRating rating, String userId) {
        FlashCard card = flashcardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException("Flashcard not found: " + cardId, 404));
        // IDOR guard: the card's avatar must belong to the caller. 404 (not 403)
        // so we don't reveal that another user's card exists.
        if (!avatarRepository.existsByIdAndUserId(card.avatarId(), userId)) {
            throw new BusinessException("Flashcard not found: " + cardId, 404);
        }
        FlashCard updated = Sm2Scheduler.applyRating(card, rating);
        return flashcardRepository.save(updated);
    }
}
