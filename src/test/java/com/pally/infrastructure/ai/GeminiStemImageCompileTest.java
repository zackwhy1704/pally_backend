package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.port.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests that the Gemini wiki compiler includes original images for STEM subjects
 * and excludes them for non-STEM subjects.
 */
@ExtendWith(MockitoExtension.class)
class GeminiStemImageCompileTest {

    @Mock private WebClient webClient;
    @Mock private ClaudeWikiCompiler claudeFallback;
    @Mock private StoragePort storagePort;

    private GeminiWikiCompiler compiler;

    @BeforeEach
    void setUp() {
        GeminiThinkingBudgetConfig thinkingCfg = new GeminiThinkingBudgetConfig();
        thinkingCfg.setThinkingBudget(java.util.Map.of("wiki-compile", 0));
        compiler = new GeminiWikiCompiler(webClient, new ObjectMapper(), claudeFallback, storagePort, org.mockito.Mockito.mock(com.pally.domain.cost.AiUsageMeter.class), thinkingCfg);
    }

    @Test
    void collectStemImages_mathsAvatar_loadsPhotoFilesFromStorage() {
        Avatar mathAvatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);
        byte[] imgBytes = new byte[]{10, 20, 30, 40};

        KnowledgeFile photoFile = KnowledgeFile.create(
                mathAvatar.getId(), "user-1", "equations.jpg", "avatars/x/equations.jpg",
                KnowledgeFile.UploadType.PHOTO);
        photoFile.setExtractedText("x^2 + 3x = 0");
        photoFile.markReady(1);

        when(storagePort.download("avatars/x/equations.jpg")).thenReturn(imgBytes);

        List<GeminiWikiCompiler.ImageData> images = compiler.collectStemImages(mathAvatar, List.of(photoFile));

        assertThat(images).hasSize(1);
        assertThat(images.getFirst().bytes()).isEqualTo(imgBytes);
        assertThat(images.getFirst().mimeType()).isEqualTo("image/jpeg");
        verify(storagePort).download("avatars/x/equations.jpg");
    }

    @Test
    void collectStemImages_nonStemAvatar_returnsEmptyList() {
        Avatar historyAvatar = Avatar.create("user-1", "HistBot", Subject.HISTORY, CharacterType.ZAP);

        KnowledgeFile photoFile = KnowledgeFile.create(
                historyAvatar.getId(), "user-1", "notes.jpg", "avatars/x/notes.jpg",
                KnowledgeFile.UploadType.PHOTO);

        List<GeminiWikiCompiler.ImageData> images = compiler.collectStemImages(historyAvatar, List.of(photoFile));

        assertThat(images).isEmpty();
        verify(storagePort, never()).download(anyString());
    }

    @Test
    void collectStemImages_pdfUpload_skippedEvenForStem() {
        Avatar scienceAvatar = Avatar.create("user-1", "SciBot", Subject.SCIENCE, CharacterType.FINN);

        KnowledgeFile pdfFile = KnowledgeFile.create(
                scienceAvatar.getId(), "user-1", "textbook.pdf", "avatars/x/textbook.pdf",
                KnowledgeFile.UploadType.PDF);

        List<GeminiWikiCompiler.ImageData> images = compiler.collectStemImages(scienceAvatar, List.of(pdfFile));

        assertThat(images).isEmpty();
        verify(storagePort, never()).download(anyString());
    }

    @Test
    void collectStemImages_capsAtMaxImages() {
        Avatar mathAvatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);
        byte[] imgBytes = new byte[]{1, 2, 3};

        // Create more than MAX_STEM_IMAGES photo files
        List<KnowledgeFile> files = new java.util.ArrayList<>();
        for (int i = 0; i < GeminiWikiCompiler.MAX_STEM_IMAGES + 5; i++) {
            String key = "avatars/x/img" + i + ".png";
            KnowledgeFile f = KnowledgeFile.create(
                    mathAvatar.getId(), "user-1", "img" + i + ".png", key,
                    KnowledgeFile.UploadType.PHOTO);
            files.add(f);
            lenient().when(storagePort.download(key)).thenReturn(imgBytes);
        }

        List<GeminiWikiCompiler.ImageData> images = compiler.collectStemImages(mathAvatar, files);

        assertThat(images).hasSize(GeminiWikiCompiler.MAX_STEM_IMAGES);
    }

    @Test
    void collectStemImages_storageFails_gracefullySkipsImage() {
        Avatar codingAvatar = Avatar.create("user-1", "CodeBot", Subject.CODING, CharacterType.BYTE);

        KnowledgeFile photoFile = KnowledgeFile.create(
                codingAvatar.getId(), "user-1", "code.jpg", "avatars/x/code.jpg",
                KnowledgeFile.UploadType.PHOTO);

        when(storagePort.download("avatars/x/code.jpg"))
                .thenThrow(new RuntimeException("Storage unavailable"));

        List<GeminiWikiCompiler.ImageData> images = compiler.collectStemImages(codingAvatar, List.of(photoFile));

        assertThat(images).isEmpty();
    }

    @Test
    void isStemSubject_coversAllStemTypes() {
        assertThat(GeminiWikiCompiler.isStemSubject(Subject.MATHS)).isTrue();
        assertThat(GeminiWikiCompiler.isStemSubject(Subject.SCIENCE)).isTrue();
        assertThat(GeminiWikiCompiler.isStemSubject(Subject.CODING)).isTrue();
        assertThat(GeminiWikiCompiler.isStemSubject(Subject.ENGLISH)).isFalse();
        assertThat(GeminiWikiCompiler.isStemSubject(Subject.HISTORY)).isFalse();
        assertThat(GeminiWikiCompiler.isStemSubject(Subject.ART)).isFalse();
    }

    @Test
    void collectStemImages_mixedPhotoAndPdf_onlyIncludesPhotos() {
        Avatar mathAvatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);
        byte[] imgBytes = new byte[]{5, 6, 7};

        KnowledgeFile photo = KnowledgeFile.create(
                mathAvatar.getId(), "user-1", "eq.png", "avatars/x/eq.png",
                KnowledgeFile.UploadType.PHOTO);
        KnowledgeFile pdf = KnowledgeFile.create(
                mathAvatar.getId(), "user-1", "notes.pdf", "avatars/x/notes.pdf",
                KnowledgeFile.UploadType.PDF);
        KnowledgeFile text = KnowledgeFile.create(
                mathAvatar.getId(), "user-1", "notes.txt", "avatars/x/notes.txt",
                KnowledgeFile.UploadType.TEXT);

        when(storagePort.download("avatars/x/eq.png")).thenReturn(imgBytes);

        List<GeminiWikiCompiler.ImageData> images = compiler.collectStemImages(
                mathAvatar, List.of(photo, pdf, text));

        assertThat(images).hasSize(1);
        assertThat(images.getFirst().mimeType()).isEqualTo("image/png");
    }
}
