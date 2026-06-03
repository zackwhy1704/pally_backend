package com.pally.api.teach;

import com.pally.api.teach.dto.TeachRequest;
import com.pally.api.teach.dto.TeachResponse;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.XpService;
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
    private final XpService xpService;
    private final AvatarSlotGuard avatarSlotGuard;

    @PostMapping
    public ResponseEntity<ApiResponse<TeachResponse>> teach(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody TeachRequest request) {
        if (request.topicSlug() == null || request.topicSlug().isBlank()) {
            throw new BusinessException("topicSlug is required", 400);
        }

        // Fix 2: Slot guard — locked avatars cannot be taught.
        avatarSlotGuard.requireActive(avatarId, userId);

        // Load wiki page — WikiRepositoryAdapter.findBy... is @Transactional(readOnly)
        // so the connection is held only for this brief read, not across the Claude call.
        WikiPage page = wikiRepository
                .findByAvatarIdAndSlug(avatarId, request.topicSlug())
                .orElseThrow(() -> new BusinessException(
                        "Wiki page not found: " + request.topicSlug(), 404));

        // Claude evaluation — can take 10–30 s. No @Transactional here:
        // the old annotation held a DB connection for the entire duration,
        // which exhausted the Hikari pool under concurrent requests.
        TeachResponse result = evaluator.evaluate(page, request.explanation());

        // Update wiki certainty based on student mastery.
        // save() is @Transactional in WikiRepositoryAdapter — own short transaction.
        updateWikiCertainty(page, result);

        // XP award — XpService.awardFlat() is transactional internally.
        if (result.xpEarned() > 0) {
            var credit = xpService.awardFlat(userId, result.xpEarned());
            activityLogService.log(userId, avatarId,
                    ActivityLogService.TYPE_QUIZ, 0, result.xpEarned());
            result = result.withLevel(credit.levelledUp(), credit.newLevel());
        }

        log.info("[Teach] user={} avatar={} slug={} score={}/{} xp={} level={} certaintyDelta={}",
                userId, avatarId, request.topicSlug(),
                result.score(), result.totalConcepts(), result.xpEarned(),
                result.newLevel(), certaintyDelta(result));

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Applies Feynman-technique feedback to the wiki page's certainty score.
     * <ul>
     *   <li>≥80 % mastery → boosts certainty, clears reviewRequired</li>
     *   <li>40–79 % → small boost, keeps reviewRequired as-is</li>
     *   <li>&lt;40 % → marks reviewRequired so the quiz surfaces it soon</li>
     * </ul>
     */
    private void updateWikiCertainty(WikiPage page, TeachResponse result) {
        if (result.totalConcepts() == 0) return;
        double ratio = (double) result.score() / result.totalConcepts();
        try {
            if (ratio >= 0.8) {
                page.adjustCertaintyScore(0.08);    // student clearly mastered it
                page.setReviewRequired(false);
            } else if (ratio >= 0.4) {
                page.adjustCertaintyScore(0.03);    // partial understanding
            } else {
                page.adjustCertaintyScore(-0.05);   // gaps detected → flag for review
                page.setReviewRequired(true);
            }
            wikiRepository.save(page);
            log.debug("[Teach] wiki certainty updated slug={} ratio={} reviewRequired={}",
                    page.getSlug(), ratio, page.isReviewRequired());
        } catch (Exception e) {
            log.warn("[Teach] Failed to update wiki certainty slug={}: {}", page.getSlug(), e.getMessage());
        }
    }

    private String certaintyDelta(TeachResponse result) {
        if (result.totalConcepts() == 0) return "n/a";
        double ratio = (double) result.score() / result.totalConcepts();
        if (ratio >= 0.8) return "+0.08";
        if (ratio >= 0.4) return "+0.03";
        return "-0.05";
    }
}
