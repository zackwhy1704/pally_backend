package com.pally.domain.quiz;

import java.util.List;

/**
 * Domain port for {@link FlashcardReview} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/quiz}.
 *
 * <p>Append-only by intent: there is no update or delete. The whole point of this
 * table is that history is not overwritten the way {@code flashcards} is.
 */
public interface FlashcardReviewRepository {

    FlashcardReview save(FlashcardReview review);

    /** One card's review sequence, oldest first — for reconstructing a recall history. */
    List<FlashcardReview> findByFlashcardIdOrderByReviewedAt(String flashcardId);

    long countByAvatarId(String avatarId);
}
