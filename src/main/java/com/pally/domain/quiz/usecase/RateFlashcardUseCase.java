package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.Sm2Scheduler;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateFlashcardUseCase {

    private final FlashcardRepository flashcardRepository;
    private final AvatarRepository avatarRepository;
    private final com.pally.domain.learning.LearningEventRepository learningEventRepository;

    public FlashCard execute(String cardId, CardRating rating, String userId) {
        FlashCard card = flashcardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException("Flashcard not found: " + cardId, 404));
        // IDOR guard: the card's avatar must belong to the caller. 404 (not 403)
        // so we don't reveal that another user's card exists.
        if (!avatarRepository.existsByIdAndUserId(card.avatarId(), userId)) {
            throw new BusinessException("Flashcard not found: " + cardId, 404);
        }
        FlashCard updated = Sm2Scheduler.applyRating(card, rating);
        FlashCard saved = flashcardRepository.save(updated);
        try {
            learningEventRepository.save(com.pally.domain.learning.LearningEvent.of(
                    userId, saved.avatarId(),
                    com.pally.domain.learning.LearningEventSource.FLASHCARD,
                    com.pally.domain.learning.LearningEventProvenance.SPACED_VERIFIED_RECALL,
                    saved.sourceSlug(), ratingScore(rating), saved.id()));
        } catch (Exception e) {
            log.warn("[LearningEvent] write failed (non-fatal): {}", e.getMessage());
        }
        return saved;
    }

    /// Normalized recall-quality proxy for the rating bucket — analogous to
    /// quiz's binary wasCorrect, but SM-2 ratings are 3-way.
    private static BigDecimal ratingScore(CardRating rating) {
        return switch (rating) {
            case EASY -> BigDecimal.ONE;
            case OKAY -> new BigDecimal("0.5");
            case HARD -> BigDecimal.ZERO;
        };
    }
}
