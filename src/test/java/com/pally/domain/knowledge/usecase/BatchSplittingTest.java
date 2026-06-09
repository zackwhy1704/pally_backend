package com.pally.domain.knowledge.usecase;

import com.pally.domain.knowledge.KnowledgeFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the batch splitting logic in CompileWikiUseCase.
 * Verifies: files are grouped under the char budget, oversized files get
 * their own batch, empty file lists produce no batches.
 */
class BatchSplittingTest {

    @Test
    void splitIntoBatches_allFitInOneBatch_singleBatch() {
        List<KnowledgeFile> files = List.of(
                makeFile("a.pdf", 10_000),
                makeFile("b.pdf", 15_000),
                makeFile("c.pdf", 20_000));

        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(files, 50_000);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(3);
    }

    @Test
    void splitIntoBatches_exceedsBudget_multipleBatches() {
        List<KnowledgeFile> files = List.of(
                makeFile("a.pdf", 30_000),
                makeFile("b.pdf", 30_000),
                makeFile("c.pdf", 30_000));

        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(files, 50_000);

        assertThat(batches).hasSize(3);
        assertThat(batches.get(0)).hasSize(1);
        assertThat(batches.get(1)).hasSize(1);
        assertThat(batches.get(2)).hasSize(1);
    }

    @Test
    void splitIntoBatches_mixedSizes_groupsEfficiently() {
        List<KnowledgeFile> files = List.of(
                makeFile("small1.pdf", 5_000),
                makeFile("small2.pdf", 5_000),
                makeFile("big.pdf", 45_000),
                makeFile("small3.pdf", 5_000));

        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(files, 50_000);

        // small1(5k)+small2(5k)=10k, +big(45k)=55k > 50k -> split before big
        // big(45k)+small3(5k)=50k <= 50k -> same batch
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(2); // small1 + small2 = 10k
        assertThat(batches.get(1)).hasSize(2); // big + small3 = 50k
    }

    @Test
    void splitIntoBatches_singleOversizedFile_itsOwnBatch() {
        List<KnowledgeFile> files = List.of(
                makeFile("huge.pdf", 100_000));

        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(files, 50_000);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(1);
    }

    @Test
    void splitIntoBatches_emptyList_noBatches() {
        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(List.of(), 50_000);

        assertThat(batches).isEmpty();
    }

    @Test
    void splitIntoBatches_filesWithNullText_treatedAsZeroChars() {
        KnowledgeFile noText = KnowledgeFile.create("av", "u", "empty.pdf",
                "key/empty.pdf", KnowledgeFile.UploadType.PDF);
        // extractedText is null by default

        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(List.of(noText), 50_000);

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).hasSize(1);
    }

    private KnowledgeFile makeFile(String name, int textLength) {
        KnowledgeFile kf = KnowledgeFile.create("avatar-1", "user-1", name,
                "key/" + name, KnowledgeFile.UploadType.PDF);
        kf.setExtractedText("x".repeat(textLength));
        kf.markReady(1);
        return kf;
    }
}
