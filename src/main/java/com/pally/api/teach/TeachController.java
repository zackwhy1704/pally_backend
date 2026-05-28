package com.pally.api.teach;

import com.pally.api.teach.dto.TeachRequest;
import com.pally.api.teach.dto.TeachResponse;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.UserRepository;
import com.pally.infrastructure.ai.ClaudeTeachEvaluator;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Teach Mochi" — Feynman-technique endpoint. The student explains a topic
 * back to the avatar; Claude scores concept coverage and awards XP.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/teach")
@RequiredArgsConstructor
@Slf4j
public class TeachController {

    private final WikiRepository wikiRepository;
    private final ClaudeTeachEvaluator evaluator;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<TeachResponse>> teach(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody TeachRequest request) {
        if (request.topicSlug() == null || request.topicSlug().isBlank()) {
            throw new BusinessException("topicSlug is required", 400);
        }
        WikiPage page = wikiRepository
                .findByAvatarIdAndSlug(avatarId, request.topicSlug())
                .orElseThrow(() -> new BusinessException(
                        "Wiki page not found: " + request.topicSlug(), 404));

        TeachResponse result = evaluator.evaluate(page, request.explanation());

        if (result.xpEarned() > 0) {
            int stars = Math.round(result.xpEarned() * 0.5f);
            userRepository.addXpAndStars(userId, result.xpEarned(), stars);
            activityLogService.log(userId, avatarId,
                    ActivityLogService.TYPE_QUIZ, 0, result.xpEarned());
        }

        log.info("[Teach] user={} avatar={} slug={} score={}/{} xp={}",
                userId, avatarId, request.topicSlug(),
                result.score(), result.totalConcepts(), result.xpEarned());

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
