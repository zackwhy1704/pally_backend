package com.pally.domain.module;

import com.pally.domain.content.OutputType;
import com.pally.domain.content.OutputValidator;
import com.pally.domain.cost.AiCostRates;
import com.pally.domain.knowledge.WikiRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // Cost-estimate assumptions for a single stage regeneration (documented, not measured):
    // a spot-mistake regen sends the page content (~1.8k input tokens) and gets one item
    // (~300 output). gemini-2.5-flash (the module-*-gen model, thinking 0). The authoritative
    // cost is the "content-reaper-regen" ledger rows post-flip; this is the pre-flip estimate.
    static final String REGEN_MODEL = "gemini-2.5-flash";
    static final long EST_INPUT_TOKENS_PER_REGEN = 1800;
    static final long EST_OUTPUT_TOKENS_PER_REGEN = 300;

    private final ModuleContentItemRepository itemRepo;
    private final OutputValidator validator;
    private final LearningModuleRepository moduleRepo;
    private final WikiRepository wikiRepo;
    private final AiCostRates costRates;

    public ContentHealthReaper(ModuleContentItemRepository itemRepo, OutputValidator validator,
                               LearningModuleRepository moduleRepo, WikiRepository wikiRepo,
                               AiCostRates costRates) {
        this.itemRepo = itemRepo;
        this.validator = validator;
        this.moduleRepo = moduleRepo;
        this.wikiRepo = wikiRepo;
        this.costRates = costRates;
    }

    /** 03:15 Asia/Singapore daily (after the deletion reapers). Does nothing unless enabled. */
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Singapore")
    public void reap() {
        if (!enabled) return;
        if (dryRun) {
            ContentDamageReport report = reportDamage();
            log.info("[ContentReaper] DRY_RUN — NO writes, NO LLM. Invalid served items: total={} "
                            + "byType={} | wouldRegenerate={} wouldRetire={} estRegenCostUsd={}",
                    report.totalInvalid(), report.invalidByType(),
                    report.wouldRegenerate(), report.wouldRetire(),
                    String.format("%.4f", report.estRegenCostMicros() / 1_000_000.0));
            return;
        }
        scanAndQuarantineBatch();
        retireSourcelessQuarantined();
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
        int total = 0, wouldRegenerate = 0, wouldRetire = 0, pageNum = 0;
        while (true) {
            List<ModuleContentItem> page = itemRepo.findServablePage(pageNum, REPORT_PAGE_SIZE);
            if (page.isEmpty()) break;
            for (ModuleContentItem item : page) {
                if (!isValid(item)) {
                    total++;
                    byType.merge(item.getType() == null ? "UNKNOWN" : item.getType(), 1, Integer::sum);
                    // Classify (the operator's flip-decision artifact): a live source → the
                    // reaper can regenerate; no source → it can only retire.
                    if (hasSource(item)) wouldRegenerate++; else wouldRetire++;
                }
            }
            if (page.size() < REPORT_PAGE_SIZE) break;
            pageNum++;
        }
        long estCost = costRates.estCostMicros(
                REGEN_MODEL,
                (long) wouldRegenerate * EST_INPUT_TOKENS_PER_REGEN,
                (long) wouldRegenerate * EST_OUTPUT_TOKENS_PER_REGEN);
        return new ContentDamageReport(total, byType, wouldRegenerate, wouldRetire, estCost);
    }

    /**
     * RETIRE-NO-SOURCE pass (LIVE reap — status write only, NEVER delete: module_progress
     * holds a FK to the item id, so deleting orphans graded history). A QUARANTINED item
     * whose WikiPage source is gone can never be regenerated → mark it terminal RETIRED
     * (still off SERVABLE_STATUSES, so never served; the row is preserved for the FK).
     * On the current corpus this is a documented no-op (all invalid items have a live
     * source — every one is would-regenerate), but it closes the branch structurally.
     */
    void retireSourcelessQuarantined() {
        int pageNum = 0, retired = 0, checked = 0;
        while (true) {
            List<ModuleContentItem> page = itemRepo.findByStatusPage(
                    ModuleContentItemRepository.STATUS_QUARANTINED, pageNum, REPORT_PAGE_SIZE);
            if (page.isEmpty()) break;
            for (ModuleContentItem item : page) {
                try {
                    checked++;
                    if (!hasSource(item)) {
                        item.setStatus(ModuleContentItemRepository.STATUS_RETIRED);
                        itemRepo.save(item);
                        retired++;
                    }
                } catch (Exception e) {
                    log.error("[ContentReaper] retire check failed item={}: {}",
                            item.getId(), e.getMessage(), e);
                }
            }
            if (page.size() < REPORT_PAGE_SIZE) break;
            pageNum++;
        }
        log.info("[ContentReaper] retire pass: checkedQuarantined={} retiredNoSource={}", checked, retired);
    }

    /** True when the item passes the persist-boundary validator (the same rules generation uses). */
    private boolean isValid(ModuleContentItem item) {
        return !validator.retainValid(List.of(item), OutputType.MODULE_ITEM).isEmpty();
    }

    /**
     * True when the item's originating WikiPage source still exists (so a regenerate has
     * input). Resolve module → (avatarId, wikiPageSlug) → wiki page. Missing module or
     * missing page → no source → would-retire.
     */
    private boolean hasSource(ModuleContentItem item) {
        if (item.getModuleId() == null) return false;
        Optional<LearningModule> module = moduleRepo.findById(item.getModuleId());
        if (module.isEmpty()) return false;
        LearningModule m = module.get();
        return wikiRepo.findByAvatarIdAndSlug(m.getAvatarId(), m.getWikiPageSlug()).isPresent();
    }

    /**
     * The DRY_RUN damage artifact. Per-type counts of invalid SERVABLE items, plus the
     * classification (would-regenerate vs would-retire by source presence) and a rough regen
     * cost estimate — the operator's flip-decision inputs before {@code dry-run=false}.
     */
    public record ContentDamageReport(int totalInvalid, Map<String, Integer> invalidByType,
                                       int wouldRegenerate, int wouldRetire,
                                       long estRegenCostMicros) {}
}
