package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Fix B at the source: the zero-READY skip must distinguish an INTENDED empty brain
 * (no files at all → archive, e.g. user deleted their last file) from a TRANSIENT
 * zero-ready (files exist but none READY — a commit-race victim or all-FAILED), where
 * archiving would WIPE an established brain. Only the skip path is exercised (execute
 * returns early), so only the two repositories it touches need mocking.
 */
@ExtendWith(MockitoExtension.class)
class CompileWikiUseCaseZeroReadyTest {

    @Mock AvatarRepository avatarRepository;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock WikiRepository wikiRepository;
    @InjectMocks CompileWikiUseCase useCase;

    @BeforeEach
    void ownerExists() {
        // lenient: the pure-inventory test exercises a static method and never calls execute().
        lenient().when(avatarRepository.findById("av")).thenReturn(Optional.of(mock(Avatar.class)));
    }

    @Test
    void noFilesAtAll_total0_archivesOrphanPages_theIntendedEmptyBrain() {
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of());

        CompileWikiUseCase.CompileResult r = useCase.execute("av");

        // Delete-last-file behaviour preserved: pages archived, NOT a retry signal.
        verify(wikiRepository).archiveOrphanPages("av", List.of());
        assertThat(r.tierServed()).isNotEqualTo("skipped-zero-ready-retry");
    }

    @Test
    void filesExistAllFailed_doesNotArchive_signalsZeroReadyFailed() {
        // A non-READY, all-FAILED file — total > 0, zero READY, nothing processing/segmented.
        KnowledgeFile failed = KnowledgeFile.create("av", "u", "f.pdf", "key", KnowledgeFile.UploadType.PDF);
        failed.markFailed(); // → FAILED (still not READY)
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of(failed));

        CompileWikiUseCase.CompileResult r = useCase.execute("av");

        // Honest signal: a real failure (not "processing" or "awaiting selection").
        // The brain is NEVER wiped on a transient zero-ready.
        assertThat(r.tierServed()).isEqualTo("zero-ready-failed");
        verify(wikiRepository, never()).archiveOrphanPages(eq("av"), anyList());
    }

    @Test
    void filesExistProcessing_signalsZeroReadyProcessing() {
        // A freshly-created file is PROCESSING (extraction still running) — total > 0, zero READY.
        KnowledgeFile processing = KnowledgeFile.create("av", "u", "big.pdf", "key", KnowledgeFile.UploadType.PDF);
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of(processing));

        CompileWikiUseCase.CompileResult r = useCase.execute("av");

        assertThat(r.tierServed()).isEqualTo("zero-ready-processing");
        verify(wikiRepository, never()).archiveOrphanPages(eq("av"), anyList());
    }

    @Test
    void filesExistSegmented_signalsAwaitingSelection_theLateSegmentationCase() {
        // The 156-page-PDF case: compile-time segmentation left a SEGMENTED parent and a
        // PENDING_CHUNK child — nothing READY, nothing PROCESSING. The honest signal is
        // "awaiting selection" (the user must pick chapters), NOT a failure or a retry loop.
        KnowledgeFile parent = KnowledgeFile.create("av", "u", "book.pdf", "key", KnowledgeFile.UploadType.PDF);
        parent.markSegmented();
        KnowledgeFile chunk = KnowledgeFile.createChunk(parent, "Chapter 1", 1, 20, 20, "chapter text");
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of(parent, chunk));

        CompileWikiUseCase.CompileResult r = useCase.execute("av");

        assertThat(r.tierServed()).isEqualTo("zero-ready-awaiting-selection");
        verify(wikiRepository, never()).archiveOrphanPages(eq("av"), anyList());
    }

    @Test
    void inventory_countsAllSixStatuses_includingSegmentedPendingChunkIrrelevant() {
        KnowledgeFile ready = KnowledgeFile.create("av", "u", "r.pdf", "key", KnowledgeFile.UploadType.PDF);
        ready.markReady(1);
        KnowledgeFile failed = KnowledgeFile.create("av", "u", "f.pdf", "key", KnowledgeFile.UploadType.PDF);
        failed.markFailed();
        KnowledgeFile processing = KnowledgeFile.create("av", "u", "p.pdf", "key", KnowledgeFile.UploadType.PDF);
        KnowledgeFile irrelevant = KnowledgeFile.create("av", "u", "i.pdf", "key", KnowledgeFile.UploadType.PDF);
        irrelevant.markIrrelevant();
        KnowledgeFile parent = KnowledgeFile.create("av", "u", "book.pdf", "key", KnowledgeFile.UploadType.PDF);
        parent.markSegmented();
        KnowledgeFile chunk = KnowledgeFile.createChunk(parent, "Ch1", 1, 2, 2, "text");

        CompileWikiUseCase.Inventory inv = CompileWikiUseCase.inventory(
                List.of(ready, failed, processing, irrelevant, parent, chunk));

        // Every status is counted — the SEGMENTED/PENDING_CHUNK/IRRELEVANT files that the
        // old ready/failed/processing-only inventory rendered invisible.
        assertThat(inv.total()).isEqualTo(6);
        assertThat(inv.ready()).isEqualTo(1);
        assertThat(inv.failed()).isEqualTo(1);
        assertThat(inv.processing()).isEqualTo(1);
        assertThat(inv.segmented()).isEqualTo(1);
        assertThat(inv.pendingChunk()).isEqualTo(1);
        assertThat(inv.irrelevant()).isEqualTo(1);
    }
}
