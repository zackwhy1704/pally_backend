package com.pally.domain.weakness;

import java.util.List;

/**
 * Domain port for reading a student's performance signals — the INPUT to the
 * WEAKNESS_PROFILE compiler head. Kept as a port so the domain never touches
 * the quiz JPA layer directly; the adapter maps quiz_question_results rows.
 */
public interface WeaknessSignalRepository {

    /**
     * Per-topic mastery for one student under one (personal) avatar.
     *
     * @param correctRatio 0.0–1.0 average correctness over [attempts] answers
     * @param attempts     how many answers back the ratio (confidence in it)
     */
    record TopicMastery(String topicSlug, double correctRatio, int attempts) {}

    /** Every topic the student has any quiz history with under [sourceAvatarId]. */
    List<TopicMastery> findTopicMastery(String userId, String sourceAvatarId);
}
