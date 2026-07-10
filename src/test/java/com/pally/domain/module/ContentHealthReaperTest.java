package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.content.RulesOutputValidator;
import com.pally.domain.cost.AiCostRates;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    @Mock LearningModuleRepository moduleRepo;
    @Mock WikiRepository wikiRepo;

    ContentHealthReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new ContentHealthReaper(itemRepo, new RulesOutputValidator(new ObjectMapper()),
                moduleRepo, wikiRepo, new AiCostRates());
        ReflectionTestUtils.setField(reaper, "enabled", true);
        ReflectionTestUtils.setField(reaper, "batchSize", 100);
        ReflectionTestUtils.setField(reaper, "scanBackoffHours", 20);
        // Live reap now runs the retire pass too; default it to an empty QUARANTINED page so
        // the scan tests aren't forced to care about it (lenient: dry-run tests never call it).
        lenient().when(itemRepo.findByStatusPage(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
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

    // ── 2.2 retire-no-source + 2.3 classification ────────────────────────────────

    private ModuleContentItem quarantined(String id, String moduleId) {
        ModuleContentItem i = blank(id);
        i.setStatus("QUARANTINED");
        i.setModuleId(moduleId);
        return i;
    }

    @Test
    void retirePass_quarantinedWithNoSource_marksRetired_neverDeletes() {
        ModuleContentItem orphan = quarantined("q1", "mod-gone");
        when(itemRepo.findByStatusPage("QUARANTINED", 0, 500)).thenReturn(List.of(orphan));
        when(moduleRepo.findById("mod-gone")).thenReturn(Optional.empty()); // source gone

        reaper.retireSourcelessQuarantined();

        assertThat(orphan.getStatus()).isEqualTo("RETIRED"); // terminal, off SERVABLE
        verify(itemRepo).save(orphan);                        // status write, not a delete
    }

    @Test
    void retirePass_quarantinedWithLiveSource_leftForRegeneration() {
        ModuleContentItem healable = quarantined("q2", "mod-live");
        LearningModule mod = new LearningModule();
        mod.setId("mod-live");
        mod.setAvatarId("av-1");
        mod.setWikiPageSlug("fractions");
        when(itemRepo.findByStatusPage("QUARANTINED", 0, 500)).thenReturn(List.of(healable));
        when(moduleRepo.findById("mod-live")).thenReturn(Optional.of(mod));
        when(wikiRepo.findByAvatarIdAndSlug("av-1", "fractions"))
                .thenReturn(Optional.of(WikiPage.create("av-1", "src", "Title", "body")));

        reaper.retireSourcelessQuarantined();

        assertThat(healable.getStatus()).isEqualTo("QUARANTINED"); // NOT retired — regen owns it
        verify(itemRepo, never()).save(healable);
    }

    @Test
    void reportDamage_classifiesInvalidBySourcePresence_andEstimatesCost() {
        ModuleContentItem regenable = blank("r1");
        regenable.setModuleId("mod-live");
        ModuleContentItem retireable = blank("r2");
        retireable.setModuleId("mod-gone");
        when(itemRepo.findServablePage(0, 500)).thenReturn(List.of(regenable, retireable));
        LearningModule mod = new LearningModule();
        mod.setId("mod-live"); mod.setAvatarId("av-1"); mod.setWikiPageSlug("frac");
        when(moduleRepo.findById("mod-live")).thenReturn(Optional.of(mod));
        when(moduleRepo.findById("mod-gone")).thenReturn(Optional.empty());
        when(wikiRepo.findByAvatarIdAndSlug("av-1", "frac"))
                .thenReturn(Optional.of(WikiPage.create("av-1", "src", "Title", "body")));

        ContentHealthReaper.ContentDamageReport report = reaper.reportDamage();

        assertThat(report.totalInvalid()).isEqualTo(2);
        assertThat(report.wouldRegenerate()).isEqualTo(1); // has a live source
        assertThat(report.wouldRetire()).isEqualTo(1);     // source gone
        assertThat(report.estRegenCostMicros()).isGreaterThan(0); // 1 regen costs something
    }
}
