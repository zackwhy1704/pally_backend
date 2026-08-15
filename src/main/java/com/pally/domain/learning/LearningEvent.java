package com.pally.domain.learning;

import com.pally.shared.util.IdGenerator;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single learning-outcome signal, additively mirrored from one of the
 * source-of-truth tables (module_progress, quiz_question_results,
 * flashcards) so a future consumer can subscribe to one stream instead of
 * integrating with every generator. {@code sourceRowId} points back at the
 * row in that source table for audit trail. {@code score} is nullable —
 * no event is ever written for an ungraded signal (see V125 migration).
 */
public record LearningEvent(
        String id,
        String userId,
        String avatarId,
        LearningEventSource source,
        LearningEventProvenance provenance,
        String topicSlug,
        BigDecimal score,
        Instant occurredAt,
        String sourceRowId) {

    public static LearningEvent of(String userId, String avatarId,
                                    LearningEventSource source, LearningEventProvenance provenance,
                                    String topicSlug, BigDecimal score, String sourceRowId) {
        return new LearningEvent(IdGenerator.newId(), userId, avatarId, source, provenance,
                topicSlug, score, Instant.now(), sourceRowId);
    }
}
