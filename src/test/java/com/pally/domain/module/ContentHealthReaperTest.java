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
    @Mock com.pally.domain.avatar.AvatarRepository avatarRepo;
    @Mock ModuleContentGenerator contentGenerator;

    ContentHealthReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new ContentHealthReaper(itemRepo, new RulesOutputValidator(new ObjectMapper()),
                moduleRepo, wikiRepo, new AiCostRates(), avatarRepo, contentGenerator);
        ReflectionTestUtils.setField(reaper, "enabled", true);
        ReflectionTestUtils.setField(reaper, "batchSize", 100);
        ReflectionTestUtils.setField(reaper, "scanBackoffHours", 20);
        ReflectionTestUtils.setField(reaper, "maxRegeneratePerRun", 50);
        ReflectionTestUtils.setField(reaper, "maxRegenerateAttempts", 2);
        // Live reap now runs the retire + regenerate passes too; default the QUARANTINED page
        // empty so the scan tests aren't forced to care (lenient: dry-run tests never call it).
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

    // ── 3.1/3.4 regenerate (LLM path) ────────────────────────────────────────────

    /** A QUARANTINED spot-mistake whose module/avatar/wiki source all resolve. */
    private ModuleContentItem healableQuarantined(String id) {
        ModuleContentItem i = quarantined(id, "mod-live");
        LearningModule mod = new LearningModule();
        mod.setId("mod-live"); mod.setAvatarId("av-1"); mod.setWikiPageSlug("frac");
        lenient().when(moduleRepo.findById("mod-live")).thenReturn(Optional.of(mod));
        lenient().when(avatarRepo.findById("av-1")).thenReturn(Optional.of(
                com.pally.domain.avatar.Avatar.create("teacher-1", "Corpus",
                        com.pally.domain.avatar.Subject.MATHS,
                        com.pally.domain.avatar.CharacterType.MOCHI)));
        lenient().when(wikiRepo.findByAvatarIdAndSlug("av-1", "frac"))
                .thenReturn(Optional.of(WikiPage.create("av-1", "frac", "Fractions", "body")));
        return i;
    }

    @Test
    void regenerate_validOutput_appendsLiveAndRetiresOldNeverDeletes() {
        ModuleContentItem q = healableQuarantined("q1");
        when(itemRepo.findByStatusPage("QUARANTINED", 0, 50)).thenReturn(List.of(q));
        // generator returns a real, VALID spot-mistake.
        when(contentGenerator.regenerateTypeItems(any(), any(), any(), eq("SPOT_MISTAKE")))
                .thenReturn(List.of(valid("new1")));

        reaper.regenerateBatch();

        verify(itemRepo).saveAll(any());               // new LIVE items appended
        assertThat(q.getStatus()).isEqualTo("RETIRED"); // old retired, not deleted
        verify(itemRepo).save(q);
    }

    @Test
    void regenerate_invalidOutput_neverSwaps_bumpsAttempt() {
        ModuleContentItem q = healableQuarantined("q2");
        when(itemRepo.findByStatusPage("QUARANTINED", 0, 50)).thenReturn(List.of(q));
        // generator returns a BLANK item — the validator drops it → nothing to swap.
        when(contentGenerator.regenerateTypeItems(any(), any(), any(), any()))
                .thenReturn(List.of(blank("bad-new")));

        reaper.regenerateBatch();

        verify(itemRepo, never()).saveAll(any());      // validate-before-swap: no append
        assertThat(q.getStatus()).isEqualTo("QUARANTINED"); // not healed, not yet exhausted
        assertThat(q.getReapAttempts()).isEqualTo(1);
    }

    @Test
    void regenerate_secondFailure_retiresTerminally() {
        ModuleContentItem q = healableQuarantined("q3");
        q.setReapAttempts(1); // already failed once
        when(itemRepo.findByStatusPage("QUARANTINED", 0, 50)).thenReturn(List.of(q));
        when(contentGenerator.regenerateTypeItems(any(), any(), any(), any()))
                .thenReturn(List.of(blank("bad-new")));

        reaper.regenerateBatch();

        assertThat(q.getReapAttempts()).isEqualTo(2);
        assertThat(q.getStatus()).isEqualTo("RETIRED"); // 2 fails → terminal, never retried forever
        verify(itemRepo, never()).saveAll(any());
    }

    @Test
    void regenerate_perRunCap_isThePageSize_soTheDbNeverReturnsMore() {
        ReflectionTestUtils.setField(reaper, "maxRegeneratePerRun", 1);
        when(itemRepo.findByStatusPage("QUARANTINED", 0, 1)).thenReturn(List.of());

        reaper.regenerateBatch();

        // the cap is pushed into the query (page size), so a huge corpus can't run away.
        verify(itemRepo).findByStatusPage("QUARANTINED", 0, 1);
    }
}
