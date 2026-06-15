package com.pally.domain.parent;

import java.util.List;

/**
 * Domain port for quiz-result weak-area queries used by the parent dashboard.
 * Each returned row is [topicSlug (String), correctRatio (double)].
 */
public interface QuizWeakAreaRepository {

    /**
     * Returns up to {@code limit} topic rows [topicSlug, correctRatio] for
     * the given user, ordered weakest-first, excluding topics with fewer
     * than 3 attempts.
     */
    List<Object[]> findWeakestTopics(String userId, int limit);
}
