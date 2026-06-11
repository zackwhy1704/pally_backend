package com.pally.domain.notification;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
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

    private final UserJpaRepository userRepo;
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
        Optional<UserJpaEntity> childOpt = userRepo.findById(childId);
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
        Optional<UserJpaEntity> childOpt = userRepo.findById(childId);
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
        Optional<UserJpaEntity> childOpt = userRepo.findById(childId);
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
