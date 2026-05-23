package com.pally.api.progress;

import com.pally.api.progress.dto.ProgressResponse;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.usecase.GetProgressUseCase;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final GetProgressUseCase getProgressUseCase;

    @GetMapping
    public ResponseEntity<ApiResponse<ProgressResponse>> getProgress(
            @RequestHeader("X-User-Id") String userId
    ) {
        ProgressSummary summary = getProgressUseCase.execute(userId);
        return ResponseEntity.ok(ApiResponse.success(ProgressResponse.from(summary)));
    }
}
