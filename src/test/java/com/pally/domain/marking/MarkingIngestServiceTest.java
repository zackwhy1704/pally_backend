package com.pally.domain.marking;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeService;
import com.pally.domain.knowledge.usecase.UploadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarkingIngestServiceTest {

    @Mock private MarkingReferenceService markingReferenceService;
    @Mock private MarkingCorpusService markingCorpusService;
    @Mock private KnowledgeService knowledgeService;
    @Mock private AvatarRepository avatarRepository;

    @InjectMocks private MarkingIngestService service;

    private MarkingReference savedRef() {
        return MarkingReference.create("class-1", MarkingReferenceKind.MARKED_PAPER,
                "A-grade exemplar", null, List.of(), "extracted");
    }

    private List<IncomingFile> twoFiles() {
        return List.of(
                new IncomingFile("a.pdf", "application/pdf", new byte[]{1, 2, 3}),
                new IncomingFile("b.pdf", "application/pdf", new byte[]{4, 5, 6}));
    }

    private Avatar corpusAvatar() {
        Avatar a = Avatar.create("owner-1", "Maths Marking Standard",
                Subject.MATHS, CharacterType.MOCHI);
        a.markMarkingCorpus();
        return a;
    }

    @Test
    void createAndIngest_storesRawRefThenCompilesFilesIntoCorpus() {
        MarkingReference saved = savedRef();
        when(markingReferenceService.create(any(), any(), any(), any(), any()))
                .thenReturn(saved);
        when(markingCorpusService.resolveOrCreateForClass("class-1")).thenReturn("av-corpus");
        when(avatarRepository.findById("av-corpus")).thenReturn(Optional.of(corpusAvatar()));
        when(knowledgeService.uploadFile(eq("owner-1"), eq("av-corpus"), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Success("f", 1, List.of()));

        MarkingReference result = service.createAndIngest(
                "class-1", MarkingReferenceKind.MARKED_PAPER, "A-grade exemplar", null, twoFiles());

        assertThat(result).isSameAs(saved);
        // Raw store first, then one upload per file into the corpus, then a compile.
        verify(markingReferenceService).create(any(), any(), any(), any(), any());
        verify(knowledgeService, times(2))
                .uploadFile(eq("owner-1"), eq("av-corpus"), any(MultipartFile.class), eq(true));
        verify(knowledgeService).compileWiki("owner-1", "av-corpus");
    }

    @Test
    void createAndIngest_whenCorpusIngestFails_stillReturnsSavedRawReference() {
        MarkingReference saved = savedRef();
        when(markingReferenceService.create(any(), any(), any(), any(), any()))
                .thenReturn(saved);
        // Corpus resolution blows up — the teacher's artifact must still be saved.
        when(markingCorpusService.resolveOrCreateForClass("class-1"))
                .thenThrow(new RuntimeException("corpus down"));

        MarkingReference result = service.createAndIngest(
                "class-1", MarkingReferenceKind.RUBRIC, "Rubric", null, twoFiles());

        assertThat(result).isSameAs(saved);
        verify(knowledgeService, never()).compileWiki(any(), any());
    }

    @Test
    void createAndIngest_skipsCompileWhenNoFilesUploadedSuccessfully() {
        when(markingReferenceService.create(any(), any(), any(), any(), any()))
                .thenReturn(savedRef());
        when(markingCorpusService.resolveOrCreateForClass("class-1")).thenReturn("av-corpus");
        when(avatarRepository.findById("av-corpus")).thenReturn(Optional.of(corpusAvatar()));
        // Every file rejected (e.g. unsupported type) → nothing to compile.
        when(knowledgeService.uploadFile(any(), any(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Failure("unsupported"));

        service.createAndIngest("class-1", MarkingReferenceKind.GUIDELINE, "G", null, twoFiles());

        verify(knowledgeService, never()).compileWiki(any(), any());
    }
}
