package com.pally.domain.marking;

import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.port.StoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarkingBackfillServiceTest {

    @Mock private MarkingReferenceRepository markingReferenceRepository;
    @Mock private MarkingCorpusService markingCorpusService;
    @Mock private MarkingIngestService markingIngestService;
    @Mock private KnowledgeRepository knowledgeRepository;
    @Mock private StoragePort storagePort;
    @Mock private OrgClassRepository orgClassRepository;

    @InjectMocks private MarkingBackfillService service;

    private MarkingReference refWithFile() {
        return MarkingReference.create("class-1", MarkingReferenceKind.MARKED_PAPER,
                "A-grade", null,
                List.of(new MarkingReferenceFile("marking/class-1/a.pdf", "a.pdf", "application/pdf", 3)),
                "extracted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void backfillClass_ingestsRawReferenceFilesWithStableName() {
        MarkingReference ref = refWithFile();
        when(markingReferenceRepository.findByClassId("class-1")).thenReturn(List.of(ref));
        when(markingCorpusService.resolveOrCreateForClass("class-1")).thenReturn("corpus-av");
        when(knowledgeRepository.findByAvatarId("corpus-av")).thenReturn(List.of());
        when(storagePort.download("marking/class-1/a.pdf")).thenReturn(new byte[]{1, 2, 3});

        int count = service.backfillClass("class-1");

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<List<IncomingFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(markingIngestService).ingestFiles(eq("class-1"), captor.capture());
        List<IncomingFile> ingested = captor.getValue();
        assertThat(ingested).hasSize(1);
        // Stable, per-reference name → idempotency marker.
        assertThat(ingested.get(0).name()).isEqualTo("mref-" + ref.getId() + "-a.pdf");
    }

    @Test
    void backfillClass_isIdempotent_skipsFilesAlreadyInCorpus() {
        MarkingReference ref = refWithFile();
        when(markingReferenceRepository.findByClassId("class-1")).thenReturn(List.of(ref));
        when(markingCorpusService.resolveOrCreateForClass("class-1")).thenReturn("corpus-av");
        KnowledgeFile existing = mock(KnowledgeFile.class);
        when(existing.getFileName()).thenReturn("mref-" + ref.getId() + "-a.pdf");
        when(knowledgeRepository.findByAvatarId("corpus-av")).thenReturn(List.of(existing));

        int count = service.backfillClass("class-1");

        assertThat(count).isZero();
        verify(markingIngestService, never()).ingestFiles(any(), any());
    }

    @Test
    void backfillOrg_iteratesEveryClassInTheOrg() {
        when(orgClassRepository.findClassIdsByOrgId("org-1"))
                .thenReturn(List.of("class-1", "class-2"));
        when(markingReferenceRepository.findByClassId(any())).thenReturn(List.of());

        int count = service.backfillOrg("org-1");

        assertThat(count).isZero();
        verify(markingReferenceRepository).findByClassId("class-1");
        verify(markingReferenceRepository).findByClassId("class-2");
    }
}
