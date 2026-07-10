package com.pally.domain.module;

import com.pally.domain.content.OutputType;
import com.pally.domain.content.OutputValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content-health reaper (Phase 1b) — finds legacy blank/invalid module content items that
 * the pre-validator era persisted (the original blank spot-mistake; the demo corpus
 * included) and stops them being served.
 *
 * <p><b>DRY_RUN = true by DEFAULT.</b> In dry-run it only COUNTS (the damage report) — it
 * never writes and never calls an LLM. A live reap (writes) requires the operator to flip
 * {@code content.reaper.dry-run=false} (and {@code enabled=true}) WITH the damage report +
 * cost estimate in hand. The reaper shape mirrors {@code DeletionPurgeReaper}: batch +
 * cursor ({@code reap_last_attempt_at}) + per-item try/catch, so it can never
 * unbounded-sweep or let a stuck item starve the batch head.
 *
 * <p>This commit is the SCAN → QUARANTINE core: a quarantined item is off
 * {@link ModuleContentItemRepository#SERVABLE_STATUSES}, so Phase 1a stops serving it
 * immediately — that alone meets the "no blank item reaches a student" bar. The
 * regenerate/retire recovery layer is a following commit.
 */
@Component
@Slf4j
public class ContentHealthReaper {

    @Value("${content.reaper.enabled:false}")
    private boolean enabled;
    @Value("${content.reaper.dry-run:true}")
    private boolean dryRun;
    @Value("${content.reaper.batch-size:100}")
    private int batchSize;
    @Value("${content.reaper.scan-backoff-hours:20}")
    private int scanBackoffHours;

    private static final int REPORT_PAGE_SIZE = 500;

    private final ModuleContentItemRepository itemRepo;
    private final OutputValidator validator;

    public ContentHealthReaper(ModuleContentItemRepository itemRepo, OutputValidator validator) {
        this.itemRepo = itemRepo;
        this.validator = validator;
    }

    /** 03:15 Asia/Singapore daily (after the deletion reapers). Does nothing unless enabled. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Singapore")
    public void reap() {
        if (!enabled) return;
        if (dryRun) {
            ContentDamageReport report = reportDamage();
            log.info("[ContentReaper] DRY_RUN — NO writes, NO LLM. Invalid served items: total={} byType={}",
                    report.totalInvalid(), report.invalidByType());
            return;
        }
        scanAndQuarantineBatch();
    }

    /**
     * One batched, cursor-advancing quarantine pass (LIVE reap — writes). Validates a batch
     * of servable items; invalid → QUARANTINED (off the servable allow-list, so Phase 1a
     * stops serving it). Every scanned item's cursor timestamp is bumped so the next run
     * advances instead of re-scanning the same head. Per-item try/catch: one bad row never
     * aborts the batch.
     */
    void scanAndQuarantineBatch() {
        Instant now = Instant.now();
        Instant retryCutoff = now.minus(scanBackoffHours, ChronoUnit.HOURS);
        List<ModuleContentItem> batch = itemRepo.findReapScanCandidates(retryCutoff, batchSize);
        int scanned = 0, quarantined = 0, failed = 0;
        for (ModuleContentItem item : batch) {
            try {
                item.setReapLastAttemptAt(now); // cursor: mark scanned so the next run advances
                if (!isValid(item)) {
                    item.setStatus(ModuleContentItemRepository.STATUS_QUARANTINED);
                    quarantined++;
                }
                itemRepo.save(item);
                scanned++;
            } catch (Exception e) {
                failed++;
                log.error("[ContentReaper] scan failed item={}: {}", item.getId(), e.getMessage(), e);
            }
        }
        log.info("[ContentReaper] scan pass: scanned={} quarantined={} failed={} batchSize={}",
                scanned, quarantined, failed, batchSize);
    }

    /**
     * Full-corpus READ-ONLY damage report (no writes, no LLM) — the DRY_RUN artifact the
     * operator reviews before flipping to a live reap. Counts, per ContentItemType, how
     * many currently-SERVABLE items fail the validator (i.e. reach students blank/invalid).
     */
    public ContentDamageReport reportDamage() {
        Map<String, Integer> byType = new LinkedHashMap<>();
        int total = 0, pageNum = 0;
        while (true) {
            List<ModuleContentItem> page = itemRepo.findServablePage(pageNum, REPORT_PAGE_SIZE);
            if (page.isEmpty()) break;
            for (ModuleContentItem item : page) {
                if (!isValid(item)) {
                    total++;
                    byType.merge(item.getType() == null ? "UNKNOWN" : item.getType(), 1, Integer::sum);
                }
            }
            if (page.size() < REPORT_PAGE_SIZE) break;
            pageNum++;
        }
        return new ContentDamageReport(total, byType);
    }

    /** True when the item passes the persist-boundary validator (the same rules generation uses). */
    private boolean isValid(ModuleContentItem item) {
        return !validator.retainValid(List.of(item), OutputType.MODULE_ITEM).isEmpty();
    }

    /** Per-type counts of invalid SERVABLE items — the DRY_RUN damage artifact. */
    public record ContentDamageReport(int totalInvalid, Map<String, Integer> invalidByType) {}
}
