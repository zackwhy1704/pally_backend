package com.pally.domain.notification;

import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends a risk-alert push to parents whose children have been inactive
 * for 6+ days. Runs every Monday at 9am SGT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAlertScheduler {

    private final UserJpaRepository userRepo;
    private final ActivityLogJpaRepository activityRepo;
    private final MilestoneNotifier milestoneNotifier;

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Singapore")
    public void checkInactiveChildren() {
        List<UserJpaEntity> children = userRepo.findByAccountType("CHILD");
        Instant sixDaysAgo = Instant.now().minus(Duration.ofDays(6));
        int alerts = 0;
        for (UserJpaEntity child : children) {
            if (child.getParentId() == null) continue;
            int activity = orZero(activityRepo.countSince(child.getId(), sixDaysAgo));
            if (activity == 0) {
                try {
                    String name = child.getChildName() != null
                            ? child.getChildName()
                            : child.getDisplayName();
                    milestoneNotifier.onInactiveChild(child.getId(), name);
                    alerts++;
                } catch (Exception e) {
                    log.error("[RiskAlert] Failed to notify for child={}: {}",
                            child.getId(), e.getMessage());
                }
            }
        }
        log.info("[RiskAlert] Checked {} children, sent {} alerts",
                children.size(), alerts);
    }

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
