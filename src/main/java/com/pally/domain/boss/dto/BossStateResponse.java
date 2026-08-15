package com.pally.domain.boss.dto;

import com.pally.domain.quiz.dto.QuizQuestionResponse;

/**
 * Server-authoritative boss battle state, as served to the client. The client
 * renders exactly this — it never computes HP or outcome locally.
 * {@code currentQuestion} is built via {@code QuizService.serveGradable} (the
 * SAME answer-exposure chokepoint the daily quiz uses), so it carries the
 * usual withheld-for-centre-quizzes rules; {@code null} once {@code defeated}.
 */
public record BossStateResponse(
        boolean active,
        String id,
        String topicSlug,
        int hpRemaining,
        int hpMax,
        boolean defeated,
        boolean rewardUnlocked,
        QuizQuestionResponse currentQuestion) {

    /** No weak topic detected (or no content to generate from) — no boss to fight. */
    public static BossStateResponse none() {
        return new BossStateResponse(false, null, null, 0, 0, false, false, null);
    }
}
