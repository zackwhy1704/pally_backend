package com.pally.domain.knowledge;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces the brain-health aggregate for a tutor avatar (audit's B-B7).
 * Counts page states in-process rather than running multiple count queries:
 * one {@code findByAvatarId} + O(n) scan suffices for any realistic tutor.
 */
@Service
@RequiredArgsConstructor
public class BrainHealthService {

    /// Pages with certaintyScore below this threshold are counted as low-confidence.
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.5;

    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;

    /**
     * Returns the health summary for the given avatar, enforcing ownership.
     *
     * @param userId   caller (must own the avatar)
     * @param avatarId target avatar
     * @return map of health metrics suitable for direct JSON serialisation
     * @throws AvatarNotFoundException if the avatar doesn't exist or isn't owned by the caller
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getHealth(String userId, String avatarId) {
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
        return body;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
