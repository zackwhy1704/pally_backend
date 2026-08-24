package com.pally.domain.account;

import com.pally.domain.account.port.OrphanedStorageObjectRepository;
import com.pally.infrastructure.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Retries storage deletes that failed during an account purge.
 *
 * <p>Each queued key is deleted again. Success removes the row — the object is
 * finally gone. Failure BUMPS the attempt counter and leaves the row in place, so
 * a terminally failing key keeps accumulating attempts and stays VISIBLE. Nothing
 * is ever dropped after N tries: silently discarding it would recreate the exact
 * defect this queue exists to fix, just on a delay.
 *
 * <p>Runs shortly after the deletion reaper (02:45) so a purge's failures get a
 * first retry the same night.
 */
@Component
public class OrphanedStorageObjectSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanedStorageObjectSweeper.class);

    /** Attempts beyond which a key is called out as needing a human. */
    static final int ATTENTION_THRESHOLD = 10;

    @Value("${account.deletion.storage-sweep-batch-size:100}")
    private int batchSize;

    private final OrphanedStorageObjectRepository repo;
    private final StorageService storageService;

    public OrphanedStorageObjectSweeper(OrphanedStorageObjectRepository repo,
                                        StorageService storageService) {
        this.repo = repo;
        this.storageService = storageService;
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Singapore")
    public void sweep() {
        List<OrphanedStorageObject> batch = repo.findOldest(batchSize);
        if (batch.isEmpty()) return;

        int cleared = 0, stillFailing = 0;
        for (OrphanedStorageObject o : batch) {
            try {
                // A missing key is success: both storage backends return normally
                // for an absent object, so an already-deleted file clears the queue
                // instead of sitting in it forever as noise.
                storageService.delete(o.storageKey());
                repo.deleteByStorageKey(o.storageKey());
                cleared++;
            } catch (Exception e) {
                repo.markRetryFailed(o.storageKey(), Instant.now(),
                        e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                stillFailing++;
                if (o.attempts() + 1 >= ATTENTION_THRESHOLD) {
                    log.error("[StorageSweeper] key={} has failed {} times — needs a human. "
                                    + "The object is still in storage.",
                            o.storageKey(), o.attempts() + 1);
                }
            }
        }
        // Queue depth is the honest metric: non-zero means real objects are still
        // out there belonging to users who were told they were erased.
        log.info("[StorageSweeper] cleared={} stillFailing={} queueDepth={}",
                cleared, stillFailing, repo.count());
    }
}
