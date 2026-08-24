package com.pally.domain.account;

import com.pally.domain.account.port.OrphanedStorageObjectRepository;
import com.pally.shared.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records a failed storage delete so the surviving object stays discoverable.
 *
 * <p>A SEPARATE BEAN on purpose. {@code REQUIRES_NEW} is applied by the Spring
 * proxy, and a self-invoked method never passes through the proxy — so putting
 * this method on {@code DeleteAccountUseCase} itself would silently give it the
 * caller's transaction and defeat the whole mechanism. (That exact self-invocation
 * trap already cost a debugging cycle on this branch, in a test helper.)
 */
@Component
public class StorageDeletionFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(StorageDeletionFailureRecorder.class);

    /** Keep the queue readable; the full stack is in the log line beside it. */
    private static final int MAX_ERROR_CHARS = 500;

    private final OrphanedStorageObjectRepository repo;

    public StorageDeletionFailureRecorder(OrphanedStorageObjectRepository repo) {
        this.repo = repo;
    }

    /**
     * Writes the failure in its OWN transaction, committed independently of the
     * purge that called it.
     *
     * <p><b>Why REQUIRES_NEW is correct here — read this before "fixing" it.</b>
     * The purge is one big {@code @Transactional} unit. If it later rolls back
     * (any downstream failure, a timeout, a constraint violation), a record
     * written in the caller's transaction would roll back WITH it — and we would
     * be back to exactly the defect being fixed: an object left in storage with
     * nothing anywhere pointing at it. The record surviving the rollback IS the
     * point. The storage object is already gone-or-not out in R2 regardless of
     * what our transaction decides; the note about it must not be conditional on
     * a database outcome it does not control.
     *
     * <p><b>Contrast with the XP-durability bug</b>, where {@code REQUIRES_NEW}
     * siblings surviving a rolled-back parent was the DEFECT (quiz XP was
     * credited even though the submission it belonged to had rolled back). Same
     * mechanism, opposite correctness. The distinction is whether the child
     * write describes something INSIDE the transaction's story (XP for a
     * submission that did not happen — must roll back) or something OUTSIDE it
     * (a side effect on a remote system that already occurred — must survive).
     * Do not pattern-match this as the same defect.
     *
     * <p>Never throws: a failure to record must not, in turn, break the purge.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String storageKey, String avatarId, Exception cause) {
        try {
            repo.recordFailure(OrphanedStorageObject.firstFailure(
                    IdGenerator.newId(), storageKey, avatarId, Instant.now(), truncate(cause)));
            log.warn("[AccountDeletion] storage delete FAILED, queued for retry key={} avatar={}",
                    storageKey, avatarId);
        } catch (Exception e) {
            // Last line of defence. If even the queue write fails the object is
            // genuinely lost, so say so loudly rather than letting it pass as a
            // successful erasure.
            log.error("[AccountDeletion] COULD NOT QUEUE a leaked storage object — "
                    + "it is now unfindable. key={} avatar={}", storageKey, avatarId, e);
        }
    }

    private static String truncate(Exception cause) {
        if (cause == null) return null;
        String msg = cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        return msg.length() <= MAX_ERROR_CHARS ? msg : msg.substring(0, MAX_ERROR_CHARS);
    }
}
