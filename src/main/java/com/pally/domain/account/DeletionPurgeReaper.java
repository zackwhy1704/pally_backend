package com.pally.domain.account;

import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * ACCOUNT DELETION Phase 1 — the irreversible purge (build order step 3).
 *
 * <p>Mirrors {@link com.pally.domain.consent.PendingParentalConsentReaper} but adds a
 * batch limit + resumable cursor the consent reaper lacks. Selects DELETION_PENDING
 * accounts whose {@code account.deletion.grace-days} window has elapsed and purges each
 * via the complete {@link DeleteAccountUseCase} engine (survivor anonymization + no-FK
 * orphan deletes + DB cascade + user row).
 *
 * <p>Invariants (LOCKED policy):
 * <ul>
 *   <li><b>Idempotent + resumable.</b> Each user is its own unit of work
 *       ({@code DeleteAccountUseCase.execute} is {@code @Transactional}); a crash
 *       mid-user rolls back that user (stays PENDING) and the next run redoes it.
 *       Anonymization is UPDATE-in-place inside that same boundary, so a crash can
 *       never leave a purged user with rows pointing at a dead id.</li>
 *   <li><b>Purge-time org re-check.</b> The 14-day grace means the world can change:
 *       an owner who acquired a non-empty centre during grace ABORTS loudly and stays
 *       PENDING — never cascade-destroying a live centre.</li>
 *   <li><b>Completed email BEFORE the row delete</b> (the address is gone after).</li>
 *   <li><b>Never half-gone.</b> Any per-user failure leaves the account PENDING with a
 *       loud log; it is retried next run, never left partially purged.</li>
 * </ul>
 */
@Component
@Slf4j
public class DeletionPurgeReaper {

    @Value("${account.deletion.grace-days:14}")
    private int graceDays;

    /// Max accounts purged per run — bounds the daily job. Purged rows vanish, so the
    /// next run picks up the next batch (resumable cursor).
    @Value("${account.deletion.purge-batch-size:50}")
    private int batchSize;

    private final UserRepository userRepo;
    private final DeleteAccountUseCase deleteAccountUseCase;
    private final CentreAccessService centreAccess;
    private final EmailService emailService;

    public DeletionPurgeReaper(UserRepository userRepo,
                               DeleteAccountUseCase deleteAccountUseCase,
                               CentreAccessService centreAccess,
                               EmailService emailService) {
        this.userRepo = userRepo;
        this.deleteAccountUseCase = deleteAccountUseCase;
        this.centreAccess = centreAccess;
        this.emailService = emailService;
    }

    /** 02:45 Asia/Singapore daily — after the consent reaper's 02:30. */
    @Scheduled(cron = "0 45 2 * * *", zone = "Asia/Singapore")
    public void reap() {
        Instant cutoff = Instant.now().minus(graceDays, ChronoUnit.DAYS);
        List<User> batch = userRepo.findDeletionPendingBefore(cutoff, batchSize);
        int purged = 0, aborted = 0, failed = 0;
        for (User u : batch) {
            String userId = u.getId();

            // Purge-time RE-CHECK: an org acquired during grace must abort, not cascade.
            if (!centreAccess.isOwnedCentreEmpty(userId)) {
                log.error("[DeletionReaper] ABORT user={} — owns a non-empty centre acquired "
                        + "during grace; stays DELETION_PENDING for manual review", userId);
                aborted++;
                continue;
            }

            // Completed email BEFORE the purge (the address is gone after the row delete).
            String email = u.getEmail();
            long startMs = System.currentTimeMillis();
            try {
                if (email != null && !email.isBlank()) {
                    safeEmail(email, "Your Apalchi account has been deleted",
                            "<p>Your Apalchi account and all of its data have been permanently "
                            + "deleted, as you requested. Thanks for studying with Mochi.</p>");
                }
                deleteAccountUseCase.execute(userId, null, null); // complete purge + user row
                purged++;
                log.info("[DeletionReaper] Purged user={} durationMs={}",
                        userId, System.currentTimeMillis() - startMs);
            } catch (Exception e) {
                // Never half-gone: the @Transactional purge rolled back — stays PENDING.
                failed++;
                log.error("[DeletionReaper] FAILED user={} — stays DELETION_PENDING: {}",
                        userId, e.getMessage(), e);
            }
        }
        if (purged > 0 || aborted > 0 || failed > 0) {
            log.info("[DeletionReaper] run complete: purged={} aborted={} failed={} batchSize={}",
                    purged, aborted, failed, batchSize);
        }
    }

    private void safeEmail(String to, String subject, String html) {
        try {
            emailService.sendHtml(to, subject, html);
        } catch (Exception e) {
            log.warn("[DeletionReaper] completed-email failed to={}: {}", to, e.getMessage());
        }
    }
}
