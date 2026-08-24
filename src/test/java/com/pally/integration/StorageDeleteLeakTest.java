package com.pally.integration;

import com.pally.domain.account.OrphanedStorageObject;
import com.pally.domain.account.OrphanedStorageObjectSweeper;
import com.pally.domain.account.StorageDeletionFailureRecorder;
import com.pally.domain.account.port.OrphanedStorageObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Pins the PDPA storage-delete leak.
 *
 * <p>THE DEFECT: {@code DeleteAccountUseCase.deleteAvatarData} deleted each
 * knowledge file's stored object and then its DB row — but the row delete ran
 * UNCONDITIONALLY, including when the storage delete threw. The purge reported
 * success while the file survived in object storage, and the row holding its
 * storage key was gone. The surviving object was therefore PERMANENTLY
 * UNFINDABLE: unreachable by the app, invisible to audit, belonging to a user
 * who had been told they were erased.
 *
 * <p>Worse in kind than the orphaned-avatar gap V132 closed: those rows were at
 * least discoverable and purgeable.
 */
class StorageDeleteLeakTest extends IntegrationTestBase {

    @Autowired private StorageDeletionFailureRecorder recorder;
    @Autowired private OrphanedStorageObjectRepository queue;
    @Autowired private OrphanedStorageObjectSweeper sweeper;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearQueue() {
        queue.findOldest(1000).forEach(o -> queue.deleteByStorageKey(o.storageKey()));
        doNothing().when(storageService).delete(anyString());
    }

    // ── 1. A throwing storage delete leaves a DISCOVERABLE record ────────────

    @Test
    void failedStorageDelete_isRecorded_soTheObjectStaysFindable() {
        recorder.record("k/leaked.pdf", "av-1", new RuntimeException("R2 down"));

        List<OrphanedStorageObject> queued = queue.findOldest(10);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).storageKey()).isEqualTo("k/leaked.pdf");
        assertThat(queued.get(0).avatarId()).isEqualTo("av-1");
        assertThat(queued.get(0).lastError()).contains("R2 down");
        assertThat(queued.get(0).attempts()).isZero();
    }

    @Test
    void theRecord_carriesNoUserIdentifier() {
        // Policy, not incidental: the storage key alone completes the deletion, so
        // retaining a user id on an ERASURE record would be personal data kept
        // without a purpose — the exposure this record exists to remove.
        recorder.record("k/leaked.pdf", "av-1", new RuntimeException("boom"));

        OrphanedStorageObject o = queue.findOldest(1).get(0);
        assertThat(o.toString()).doesNotContain("user");
        // Structural proof: the record type exposes no user field at all.
        assertThat(OrphanedStorageObject.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("userId");
    }

    // ── 2. THE POINT OF REQUIRES_NEW: the record survives a purge rollback ───

    @Test
    void theRecord_SURVIVES_aRollbackOfTheCallingTransaction() {
        // This is the whole reason the recorder uses REQUIRES_NEW. If the record
        // were written in the caller's transaction, a purge that later rolls back
        // would take the record with it — and the object would be leaked with
        // nothing pointing at it, i.e. the original defect, intact.
        //
        // Proven by actually rolling back a real transaction, not asserted.
        try {
            transactionTemplate.execute(status -> {
                recorder.record("k/survives-rollback.pdf", "av-9",
                        new RuntimeException("storage exploded"));
                throw new IllegalStateException("force the caller to roll back");
            });
        } catch (IllegalStateException expected) {
            // the outer transaction rolled back
        }

        assertThat(queue.findOldest(10))
                .as("the leaked-object record MUST outlive the rolled-back purge")
                .extracting(OrphanedStorageObject::storageKey)
                .contains("k/survives-rollback.pdf");
    }

    // ── 3. "key not found" is success — no record ────────────────────────────

    @Test
    void aStorageDeleteThatSucceeds_producesNoRecord() {
        // Both backends treat an absent key as success (LocalStorageService uses
        // Files.deleteIfExists; an S3/R2 DeleteObject on a missing key returns
        // normally), so nothing throws and nothing is queued. Recording those
        // would fill the queue with objects that were already gone and turn the
        // alertable signal into noise.
        doNothing().when(storageService).delete(anyString());

        storageService.delete("k/already-gone.pdf");

        assertThat(queue.findOldest(10)).isEmpty();
        assertThat(queue.count()).isZero();
    }

    // ── 4. Sweeper: retries, increments, never silently drops ────────────────

    @Test
    void sweeper_clearsTheKeyWhenTheRetrySucceeds() {
        recorder.record("k/transient.pdf", "av-2", new RuntimeException("timeout"));
        doNothing().when(storageService).delete(anyString());

        sweeper.sweep();

        assertThat(queue.findOldest(10))
                .as("a successful retry means the object is finally gone")
                .isEmpty();
    }

    @Test
    void sweeper_incrementsAttempts_andKeepsATerminallyFailingKeyVISIBLE() {
        recorder.record("k/dead.pdf", "av-3", new RuntimeException("gone bad"));
        doThrow(new RuntimeException("still broken")).when(storageService).delete(anyString());

        sweeper.sweep();
        sweeper.sweep();
        sweeper.sweep();

        List<OrphanedStorageObject> queued = queue.findOldest(10);
        assertThat(queued)
                .as("a key that keeps failing must NEVER be silently dropped — "
                        + "discarding it would recreate the leak on a delay")
                .hasSize(1);
        assertThat(queued.get(0).attempts())
                .as("each retry must be counted so the failure is measurable")
                .isEqualTo(3);
        assertThat(queued.get(0).lastError()).contains("still broken");
        assertThat(queued.get(0).lastAttemptAt()).isNotNull();
    }

    @Test
    void recordingTheSameKeyTwice_doesNotDuplicateTheQueueEntry() {
        // Queue depth must measure LEAKED OBJECTS, not retries.
        recorder.record("k/same.pdf", "av-4", new RuntimeException("one"));
        recorder.record("k/same.pdf", "av-4", new RuntimeException("two"));

        assertThat(queue.count()).isEqualTo(1);
    }
}
