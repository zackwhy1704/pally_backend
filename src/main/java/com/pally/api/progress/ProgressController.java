package com.pally.api.progress;

import com.pally.api.progress.dto.ProgressResponse;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.usecase.GetProgressUseCase;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final GetProgressUseCase getProgressUseCase;

    @GetMapping
    public ResponseEntity<ApiResponse<ProgressResponse>> getProgress(
            @AuthenticationPrincipal String userId
    ) {
        ProgressSummary summary = getProgressUseCase.execute(userId);
        return ResponseEntity.ok(ApiResponse.success(ProgressResponse.from(summary)));
    }

    @GetMapping("/study-plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudyPlan(
            @AuthenticationPrincipal String userId
    ) {
        // Stub — returns a static study plan until StudyPlanGenerator is wired
        var plan = Map.<String, Object>of(
            "todayTasks", List.of(
                Map.of("title", "Review Photosynthesis flashcards", "done", false),
                Map.of("title", "Complete daily quiz", "done", false),
                Map.of("title", "Read Chapter 3 notes", "done", true)
            ),
            "upcomingTasks", List.of(
                Map.of("day", "Tomorrow", "title", "Practice cell structure questions"),
                Map.of("day", "Wed", "title", "Upload new Science notes"),
                Map.of("day", "Thu", "title", "Revision quiz — ecosystems")
            ),
            "testCountdown", Map.of(
                "label", "Science Test",
                "daysLeft", 14
            )
        );
        return ResponseEntity.ok(ApiResponse.success(plan));
    }
}
