package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.FlashcardReview;
import com.pally.domain.quiz.FlashcardReviewRepository;
import com.pally.domain.quiz.Sm2Scheduler;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateFlashcardUseCase {

    private final FlashcardRepository flashcardRepository;
    private final AvatarRepository avatarRepository;
    private final FlashcardReviewRepository flashcardReviewRepository;

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

        // Append the review to history (V131). The save above is DESTRUCTIVE — it
        // overwrites the card's prior SM-2 state on the same primary key — so this
        // is the only place the pre-rating state still exists. Recorded with `card`
        // (before) and `saved` (after) so a genuine repeat recall stays
        // distinguishable from a first attempt or a HARD-triggered reset.
        //
        // BEST-EFFORT: instrumentation must never cost a student their rating. A
        // failure here is logged and swallowed — the SM-2 update is already
        // committed and the student's review still counts.
        try {
            flashcardReviewRepository.save(
                    FlashcardReview.of(IdGenerator.newId(), card, saved, rating, Instant.now()));
        } catch (Exception e) {
            log.warn("[FlashcardReview] history insert failed for card={} (non-fatal): {}",
                    cardId, e.getMessage());
        }

        return saved;
    }
}
