package com.pally.domain.notification;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.push.FcmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends push notifications to parents when their child hits milestones.
 *
 * <p>Enforces a 3:1 positive-to-alert ratio by tracking weekly push counts
 * in-memory per parent. Resets naturally on server restart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MilestoneNotifier {

    private final UserRepository userRepo;
    private final FcmService fcmService;

    // Track counts in memory: parentId -> (positive, alerts)
    private final ConcurrentHashMap<String, WeekCounters> counters = new ConcurrentHashMap<>();

    private static class WeekCounters {
        final AtomicInteger positive = new AtomicInteger();
        final AtomicInteger alerts = new AtomicInteger();
    }

    /**
     * Notify parent that their child completed a module.
     */
    public void onModuleCompleted(String childId, String moduleName, double mastery) {
        Optional<User> childOpt = userRepo.findById(childId);
        if (childOpt.isEmpty() || childOpt.get().getParentId() == null) return;

        String parentId = childOpt.get().getParentId();
        String childName = childOpt.get().getChildName() != null
                ? childOpt.get().getChildName()
                : (childOpt.get().getDisplayName() != null
                        ? childOpt.get().getDisplayName() : "Your child");

        int pct = (int) Math.round(mastery * 100);
        String title = childName + " completed a module!";
        String body = moduleName + " — " + pct + "% mastery";

        sendPositive(parentId, title, body);
    }

    /**
     * Notify parent of a streak milestone.
     */
    public void onStreakMilestone(String childId, int days) {
        Optional<User> childOpt = userRepo.findById(childId);
        if (childOpt.isEmpty() || childOpt.get().getParentId() == null) return;

        String parentId = childOpt.get().getParentId();
        String childName = childOpt.get().getChildName() != null
                ? childOpt.get().getChildName()
                : (childOpt.get().getDisplayName() != null
                        ? childOpt.get().getDisplayName() : "Your child");

        String title = childName + " hit a " + days + "-day streak!";
        String body = "Keep encouraging them!";

        sendPositive(parentId, title, body);
    }

    /**
     * Notify the STUDENT that a trusted adult finished reviewing one of their
     * wiki pages. Approved → an encouraging "looks good" push; flagged → a
     * gentle "left a note" nudge. Pushes directly to the student (not the
     * parent), so it bypasses the 3:1 parent-alert ratio.
     */
    public void onReviewCompleted(String studentId, String pageTitle,
                                  boolean approved, String reviewerName) {
        if (studentId == null) return;
        String reviewer = (reviewerName != null && !reviewerName.isBlank())
                ? reviewerName : "A reviewer";
        String title;
        String body;
        if (approved) {
            title = "✅ Your " + pageTitle + " guide was checked!";
            body = reviewer + " checked your " + pageTitle + " guide — looks good!";
        } else {
            title = "👀 A note on your " + pageTitle + " guide";
            body = reviewer + " left a note on your " + pageTitle + " guide. Take a look.";
        }
        fcmService.sendToUser(studentId, title, body);
        log.info("[Review] Completed push to student={} approved={}", studentId, approved);
    }

    /**
     * Notify the child's PARENT that the child asked them to review a study
     * guide. Resolves the parent via the child's parentId (same pattern as the
     * milestone pushes); no-op if the child has no linked parent. The review
     * URL rides in the FCM data field so the parent app can deep-link.
     */
    public void onParentReviewRequested(String childId, String pageTitle, String url) {
        Optional<User> childOpt = userRepo.findById(childId);
        if (childOpt.isEmpty() || childOpt.get().getParentId() == null) return;

        String parentId = childOpt.get().getParentId();
        String displayName = childOpt.get().getChildName() != null
                ? childOpt.get().getChildName()
                : childOpt.get().getDisplayName();
        String childFirstName = firstName(displayName);

        String title = "Review request";
        String body = childFirstName + " asked you to check their " + pageTitle
                + " study guide (2 min).";
        fcmService.sendToUser(parentId, title, body, java.util.Map.of("reviewUrl", url));
        log.info("[Review] Parent-review-requested push to parent={} for child={}", parentId, childId);
    }

    private String firstName(String displayName) {
        if (displayName == null || displayName.isBlank()) return "Your child";
        String trimmed = displayName.strip();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    private void sendPositive(String parentId, String title, String body) {
        WeekCounters c = counters.computeIfAbsent(parentId, k -> new WeekCounters());
        c.positive.incrementAndGet();
        fcmService.sendToUser(parentId, title, body);
        log.info("[Milestone] Positive push to parent={}: {}", parentId, title);
    }

    /**
     * Notify parent that their child has been inactive for 6+ days.
     * Uses the alert path (subject to 3:1 suppression).
     */
    public void onInactiveChild(String childId, String childName) {
        Optional<User> childOpt = userRepo.findById(childId);
        if (childOpt.isEmpty() || childOpt.get().getParentId() == null) return;

        String parentId = childOpt.get().getParentId();
        String name = childName != null && !childName.isBlank() ? childName : "Your child";
        String title = name + " hasn't studied in 6 days";
        String body = "A gentle nudge might help 💪";

        sendAlert(parentId, title, body);
    }

    private void sendAlert(String parentId, String title, String body) {
        WeekCounters c = counters.computeIfAbsent(parentId, k -> new WeekCounters());
        // 3:1 ratio: only send alert if we've sent 3x more positives
        if (c.positive.get() < (c.alerts.get() + 1) * 3) {
            log.debug("[Milestone] Suppressing alert to parent={} (3:1 ratio)", parentId);
            return;
        }
        c.alerts.incrementAndGet();
        fcmService.sendToUser(parentId, title, body);
        log.info("[Milestone] Alert push to parent={}: {}", parentId, title);
    }
}
