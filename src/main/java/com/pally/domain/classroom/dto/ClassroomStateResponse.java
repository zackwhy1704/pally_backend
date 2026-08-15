package com.pally.domain.classroom.dto;

import com.pally.domain.quiz.dto.QuizQuestionResponse;

/**
 * Server-authoritative live classroom session state. {@code currentQuestion}
 * is built through the SAME {@code QuizService.serveGradable} chokepoint the
 * daily quiz and solo boss battle use, so grade-integrity/exposure rules
 * apply identically. {@code participantCount} is the ONLY participant-shaped
 * data here — no nickname list, no per-student identity (see
 * {@link com.pally.domain.classroom.ClassroomEventBus}).
 */
public record ClassroomStateResponse(
        String sessionId,
        String status,
        String topicSlug,
        int hpRemaining,
        int hpMax,
        boolean defeated,
        int participantCount,
        QuizQuestionResponse currentQuestion) {
}
