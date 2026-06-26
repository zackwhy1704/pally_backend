package com.pally.domain.consent;

import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Part B retention: an under-13 account whose parent never approves stays in
 * {@code PENDING_CONSENT} (blocked from uploading) and is DELETED with its data
 * {@code consent.pending-retention-days} after signup if still unapproved.
 *
 * <p>The 7-day approval token already expires on its own (the child stays pending =
 * suspended in the meantime). This job is the final delete. The retention window is
 * DPO-overridable via {@code consent.pending-retention-days} (default 30).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingParentalConsentReaper {

    /// DPO-overridable retention window. 30 days from signup, then delete if unapproved.
    @Value("${consent.pending-retention-days:30}")
    private int retentionDays;

    private final UserJpaRepository userRepo;
    private final ConsentRepository consentRepository;
    private final DeleteAccountUseCase deleteAccountUseCase;

    /** 02:30 Asia/Singapore daily. */
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Singapore")
    public void reap() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<UserJpaEntity> stale = userRepo.findByAccountStatusAndCreatedAtBefore(
                ConsentGuard.STATUS_PENDING, cutoff);
        int deleted = 0;
        for (UserJpaEntity u : stale) {
            // Safety: never delete one whose parent DID approve (status should already
            // be ACTIVE then, but double-check the consent request before erasing).
            boolean approved = consentRepository
                    .findLatestRequestByChildUserIdAndStatus(
                            u.getId(), ConsentRepository.ConsentRequest.STATUS_APPROVED)
                    .isPresent();
            if (approved) {
                continue;
            }
            try {
                deleteAccountUseCase.execute(u.getId(), null, null); // background job — no token to revoke
                deleted++;
                log.info("[ConsentReaper] Deleted unapproved under-13 account user={} "
                        + "(>{}d pending)", u.getId(), retentionDays);
            } catch (Exception e) {
                log.error("[ConsentReaper] Failed to delete pending account user={}: {}",
                        u.getId(), e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("[ConsentReaper] Reaped {} unapproved under-13 account(s)", deleted);
        }
    }
}
