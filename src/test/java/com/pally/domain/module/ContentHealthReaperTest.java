package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.content.RulesOutputValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the content-health reaper's LOCKED invariants: DRY_RUN never writes; a live scan
 * QUARANTINES the original blank spot-mistake (which Phase 1a then stops serving) while
 * leaving valid content LIVE; the full-corpus report counts damage per type; and one bad
 * row never aborts the batch. Uses the REAL RulesOutputValidator (no drift from generation).
 */
@ExtendWith(MockitoExtension.class)
class ContentHealthReaperTest {

    @Mock ModuleContentItemRepository itemRepo;

    ContentHealthReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new ContentHealthReaper(itemRepo, new RulesOutputValidator(new ObjectMapper()));
        ReflectionTestUtils.setField(reaper, "enabled", true);
        ReflectionTestUtils.setField(reaper, "batchSize", 100);
        ReflectionTestUtils.setField(reaper, "scanBackoffHours", 20);
    }

    private ModuleContentItem item(String id, String content, String answer) {
        ModuleContentItem i = new ModuleContentItem();
        i.setId(id);
        i.setType("SPOT_MISTAKE");
        i.setStage("TEST");
        i.setStatus("LIVE");
        i.setContentJson(content);
        i.setAnswerJson(answer);
        return i;
    }

    /** The original blank spot-mistake: required fields present but empty. */
    private ModuleContentItem blank(String id) {
        return item(id, "{\"problem\":\"\",\"wrongSolution\":\"\"}",
                "{\"errorDescription\":\"\",\"correctSolution\":\"\"}");
    }

    private ModuleContentItem valid(String id) {
        return item(id, "{\"problem\":\"2+2\",\"wrongSolution\":\"5\"}",
                "{\"errorDescription\":\"added wrong\",\"correctSolution\":\"4\"}");
    }

    @Test
    void liveScan_quarantinesBlank_leavesValidLive_andBumpsCursor() {
        ReflectionTestUtils.setField(reaper, "dryRun", false);
        ModuleContentItem bad = blank("bad");
        ModuleContentItem good = valid("good");
        when(itemRepo.findReapScanCandidates(any(), eq(100))).thenReturn(List.of(bad, good));

        reaper.reap();

        assertThat(bad.getStatus()).isEqualTo("QUARANTINED"); // off SERVABLE_STATUSES → 1a stops serving it
        assertThat(good.getStatus()).isEqualTo("LIVE");
        assertThat(bad.getReapLastAttemptAt()).isNotNull();    // cursor bumped so the next run advances
        assertThat(good.getReapLastAttemptAt()).isNotNull();
        verify(itemRepo).save(bad);
        verify(itemRepo).save(good);
    }

    @Test
    void dryRun_countsButNeverWrites_andNeverScansForQuarantine() {
        ReflectionTestUtils.setField(reaper, "dryRun", true);
        when(itemRepo.findServablePage(0, 500)).thenReturn(List.of(blank("b1"), valid("g1")));

        reaper.reap();

        verify(itemRepo, never()).save(any());                       // ZERO writes in dry-run
        verify(itemRepo, never()).findReapScanCandidates(any(), anyInt()); // dry-run reports, never quarantines
    }

    @Test
    void reportDamage_countsInvalidServableItemsPerType() {
        when(itemRepo.findServablePage(0, 500))
                .thenReturn(List.of(blank("b1"), blank("b2"), valid("g1")));

        ContentHealthReaper.ContentDamageReport report = reaper.reportDamage();

        assertThat(report.totalInvalid()).isEqualTo(2);
        assertThat(report.invalidByType()).containsEntry("SPOT_MISTAKE", 2);
    }

    @Test
    void liveScan_oneRowThrows_batchStillProcessesTheRest() {
        ReflectionTestUtils.setField(reaper, "dryRun", false);
        ModuleContentItem bad = blank("bad");
        ModuleContentItem good = valid("good");
        when(itemRepo.findReapScanCandidates(any(), anyInt())).thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("db down")).when(itemRepo).save(bad);

        reaper.reap();

        verify(itemRepo).save(good); // the good item is still processed despite bad throwing
    }
}
