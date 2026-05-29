package com.pally.api.knowledge;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Knowledge-health aggregate per tutor (audit's B-B7). Makes the
 * otherwise-invisible harness state surfaceable to student + parent +
 * production debugging: how many pages are wobbly, how many are flagged
 * for review, how many are archived, what the average certainty trend is.
 *
 * <p>One read of {@code findByAvatarId} + in-process counts — for any
 * realistic tutor (≤ a few hundred pages) the simplicity beats a half-
 * dozen dedicated count queries.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/brain-health")
@RequiredArgsConstructor
public class BrainHealthController {

    /// certaintyScore below this counts as "low confidence" in the
    /// summary. Aligned with the wiki's INFERRED-vs-VERIFIED gap.
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;

    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> health(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        // Ownership check — avatars are user-scoped.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        int total = pages.size();
        int lowConfidence = 0;
        int reviewFlagged = 0;
        int hasConflict = 0;
        int archived = 0;
        double certaintySum = 0.0;
        int certaintyN = 0;
        for (WikiPage p : pages) {
            if (p.getStatus() == WikiPage.Status.ARCHIVED) {
                archived++;
                continue; // archived pages don't drag the live average
            }
            if (p.isReviewRequired()) reviewFlagged++;
            if (p.isHasConflict()) hasConflict++;
            if (p.getCertaintyScore() < LOW_CONFIDENCE_THRESHOLD) lowConfidence++;
            certaintySum += p.getCertaintyScore();
            certaintyN++;
        }
        double avg = certaintyN == 0 ? 0.0 : certaintySum / certaintyN;

        Map<String, Object> body = new HashMap<>();
        body.put("avatarId", avatarId);
        body.put("totalPages", total);
        body.put("activePages", total - archived);
        body.put("archivedPages", archived);
        body.put("lowConfidencePages", lowConfidence);
        body.put("reviewFlaggedPages", reviewFlagged);
        body.put("conflictPages", hasConflict);
        body.put("averageCertainty", round(avg));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
