package com.pally.domain.marking;

import com.pally.domain.homework.DocumentTextExtractionPort;
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The marking assistant's job is to turn a teacher's uploaded papers into
 * grounding the AI marks against. These tests pin the invariants in plain
 * English: an upload is stored + indexed, validation refuses junk, the grounding
 * text is labelled by kind and bounded, and a delete also clears the artifacts.
 */
@ExtendWith(MockitoExtension.class)
class MarkingReferenceServiceTest {

    @Mock private MarkingReferenceRepository repository;
    @Mock private StoragePort storagePort;
    @Mock private DocumentTextExtractionPort textExtractor;

    private MarkingReferenceService service;

    @BeforeEach
    void setUp() {
        service = new MarkingReferenceService(repository, storagePort, textExtractor);
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static IncomingFile file() {
        return new IncomingFile("exemplar.pdf", "application/pdf", new byte[]{1, 2, 3});
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    void create_storesArtifactExtractsTextAndIndexesChars() {
        when(textExtractor.extract(any(), anyString()))
                .thenReturn("Q1 (2 marks): award 1 for method, 1 for answer.");

        MarkingReference saved = service.create(
                "class-1", MarkingReferenceKind.MARKED_PAPER, "2023 SA2 A-grade", "full working",
                List.of(file()));

        verify(storagePort).upload(anyString(), any(), anyString());
        assertThat(saved.getKind()).isEqualTo(MarkingReferenceKind.MARKED_PAPER);
        assertThat(saved.getTitle()).isEqualTo("2023 SA2 A-grade");
        assertThat(saved.getNote()).isEqualTo("full working");
        assertThat(saved.getExtractedText()).contains("award 1 for method");
        assertThat(saved.getExtractedChars()).isGreaterThan(0);
    }

    @Test
    void create_anUnreadableFileIsStillStoredWithZeroIndexedChars() {
        when(textExtractor.extract(any(), anyString())).thenReturn("");

        MarkingReference saved = service.create(
                "class-1", MarkingReferenceKind.RUBRIC, "Mark scheme", null, List.of(file()));

        verify(storagePort).upload(anyString(), any(), anyString());
        assertThat(saved.getExtractedChars()).isZero();
    }

    @Test
    void create_blankTitleIsRejectedAndNothingStored() {
        assertThatThrownBy(() -> service.create(
                "class-1", MarkingReferenceKind.GUIDELINE, "   ", null, List.of(file())))
                .isInstanceOf(BusinessException.class);
        verify(storagePort, never()).upload(anyString(), any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void create_noFilesIsRejected() {
        assertThatThrownBy(() -> service.create(
                "class-1", MarkingReferenceKind.GUIDELINE, "Guideline", null, List.of()))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    // ── grounding (MarkingReferenceContextPort) ─────────────────────────────

    @Test
    void contextForClass_withNoReferencesReturnsEmptyNotNull() {
        when(repository.findByClassId("class-1")).thenReturn(List.of());

        String ctx = service.contextForClass("class-1", 8_000);

        assertThat(ctx).isEmpty();
    }

    @Test
    void contextForClass_labelsEachReferenceByKindAndIncludesItsText() {
        MarkingReference exemplar = MarkingReference.create(
                "class-1", MarkingReferenceKind.MARKED_PAPER, "A-grade script", "clean working",
                List.of(), "Award full marks when the method is shown.");
        when(repository.findByClassId("class-1")).thenReturn(List.of(exemplar));

        String ctx = service.contextForClass("class-1", 8_000);

        assertThat(ctx).contains("MARKED EXEMPLAR");
        assertThat(ctx).contains("A-grade script");
        assertThat(ctx).contains("clean working");
        assertThat(ctx).contains("Award full marks when the method is shown.");
    }

    @Test
    void contextForClass_isBoundedByMaxChars() {
        String big = "x".repeat(20_000);
        MarkingReference ref = MarkingReference.create(
                "class-1", MarkingReferenceKind.RUBRIC, "Huge rubric", null, List.of(), big);
        when(repository.findByClassId("class-1")).thenReturn(List.of(ref));

        String ctx = service.contextForClass("class-1", 8_000);

        assertThat(ctx.length()).isLessThanOrEqualTo(8_000);
    }

    // ── delete ──────────────────────────────────────────────────────────────

    @Test
    void delete_removesStoredArtifactsThenTheRow() {
        MarkingReference ref = MarkingReference.create(
                "class-1", MarkingReferenceKind.MARKED_PAPER, "Paper", null,
                List.of(new MarkingReferenceFile("marking/class-1/k.pdf", "k.pdf", "application/pdf", 3)),
                "text");
        when(repository.findById(ref.getId())).thenReturn(java.util.Optional.of(ref));

        service.delete(ref.getId());

        verify(storagePort).delete("marking/class-1/k.pdf");
        verify(repository).deleteById(ref.getId());
    }

    @Test
    void delete_unknownReferenceThrows404() {
        when(repository.findById("nope")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.delete("nope"))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).deleteById(anyString());
    }
}
