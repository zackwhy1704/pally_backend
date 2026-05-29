package com.pally.api.progress;

import com.pally.api.progress.dto.ProgressResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.usecase.GetProgressUseCase;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/progress")
@RequiredArgsConstructor
@Slf4j
public class ProgressController {

    private final GetProgressUseCase getProgressUseCase;
    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;
    private final QuizQuestionResultJpaRepository quizResultRepo;

    @GetMapping
    public ResponseEntity<ApiResponse<ProgressResponse>> getProgress(
            @AuthenticationPrincipal String userId
    ) {
        ProgressSummary summary = getProgressUseCase.execute(userId);
        return ResponseEntity.ok(ApiResponse.success(ProgressResponse.from(summary)));
    }

    /// Adaptive study plan: today's tasks come from real signals — due
    /// flashcards per avatar, untaken daily quizzes, weakest topics.
    /// Replaces the previous hardcoded stub.
    @GetMapping("/study-plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudyPlan(
            @AuthenticationPrincipal String userId
    ) {
        List<Map<String, Object>> todayTasks = new ArrayList<>();

        List<Avatar> avatars = avatarRepository.findByUserId(userId);
        for (Avatar avatar : avatars) {
            // Due flashcards
            List<FlashCard> due = flashcardRepository.findDueByAvatarId(avatar.getId());
            if (!due.isEmpty()) {
                todayTasks.add(Map.of(
                        "title", "Review " + due.size() + " flashcard"
                                + (due.size() == 1 ? "" : "s") + " — " + avatar.getName(),
                        "type", "flashcard",
                        "avatarId", avatar.getId(),
                        "done", false
                ));
            }

            // Daily quiz if not taken today (best-effort — query may fail on empty table)
            boolean takenToday;
            try {
                Boolean b = quizResultRepo.takenToday(userId, avatar.getId());
                takenToday = b != null && b;
            } catch (Exception ignored) {
                takenToday = false;
            }
            if (!takenToday) {
                todayTasks.add(Map.of(
                        "title", "Daily quiz — " + avatar.getName(),
                        "type", "quiz",
                        "avatarId", avatar.getId(),
                        "done", false
                ));
            }
        }

        // Weak-topic practice — top 3 weakest topics across user
        List<Map<String, Object>> upcomingTasks = new ArrayList<>();
        try {
            List<Object[]> weakTopics = quizResultRepo.findWeakestTopics(userId, 3);
            for (Object[] row : weakTopics) {
                String topic = (String) row[0];
                int pct = (int) Math.round(((Number) row[1]).doubleValue() * 100);
                upcomingTasks.add(Map.of(
                        "day", "Soon",
                        "title", "Practice " + topic + " (" + pct + "% mastery)"
                ));
            }
        } catch (Exception ignored) {}

        if (todayTasks.isEmpty()) {
            todayTasks.add(Map.of(
                    "title", "Create your first tutor to get started",
                    "type", "info",
                    "done", false
            ));
        }

        var plan = Map.<String, Object>of(
                "todayTasks", todayTasks,
                "upcomingTasks", upcomingTasks,
                "testCountdown", Map.of()
        );
        return ResponseEntity.ok(ApiResponse.success(plan));
    }

    /**
     * No-op acknowledgement so the optimistic "tick task done" call from the
     * frontend stops 404-ing in the logs. The plan is recomputed per request
     * from real signals (due cards, untaken quizzes, weak topics), so there
     * is no per-task state to mutate.
     */
    @PostMapping("/study-plan/{taskId}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markStudyPlanTaskDone(
            @AuthenticationPrincipal String userId,
            @PathVariable String taskId) {
        log.debug("[StudyPlan] Task {} marked done by user {}", taskId, userId);
    }
}
