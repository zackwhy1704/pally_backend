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

    // ── Within-file segmentation (the fix for a single huge file timing out) ──

    @Test
    void windowOversizedFiles_splitsBigFile_allSegmentsShareOneFileId() {
        KnowledgeFile big = makeFile("book.pdf", 120_000);
        String id = big.getId();

        List<KnowledgeFile> units =
                CompileWikiUseCase.windowOversizedFiles(List.of(big), 50_000);

        assertThat(units.size()).isGreaterThanOrEqualTo(3); // 120k → 3+ windows
        assertThat(units).allSatisfy(u -> {
            assertThat(u.getId()).isEqualTo(id); // provenance preserved across segments
            assertThat(u.getExtractedText().length()).isLessThanOrEqualTo(50_000);
        });
        assertThat(units.get(0).getFileName()).contains("part 1/");
    }

    @Test
    void windowOversizedFiles_smallFilePassesThroughUnchanged() {
        KnowledgeFile small = makeFile("notes.pdf", 10_000);
        List<KnowledgeFile> units =
                CompileWikiUseCase.windowOversizedFiles(List.of(small), 50_000);
        assertThat(units).containsExactly(small); // same instance, not segmented
    }

    @Test
    void oversizedFile_afterSegmenting_producesMultipleBatches() {
        // THE FIX: a single 120k-char file used to be one indivisible batch
        // (→ compile timeout). After segmenting it becomes several.
        KnowledgeFile big = makeFile("book.pdf", 120_000);
        List<KnowledgeFile> units =
                CompileWikiUseCase.windowOversizedFiles(List.of(big), 50_000);
        List<List<KnowledgeFile>> batches =
                CompileWikiUseCase.splitIntoBatches(units, 50_000);
        assertThat(batches.size()).isGreaterThan(1);
    }

    @Test
    void windowText_coversText_eachWithinBudget() {
        List<String> segs = com.pally.domain.knowledge.util.TextWindower.window("x".repeat(130_000), 50_000, 800);
        assertThat(segs.size()).isGreaterThanOrEqualTo(3);
        assertThat(segs).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(50_000));
    }

    @Test
    void windowText_backsOffToParagraphBoundary() {
        String text = "a".repeat(49_500) + "\n\n" + "b".repeat(20_000);
        List<String> segs = com.pally.domain.knowledge.util.TextWindower.window(text, 50_000, 800);
        assertThat(segs.get(0)).endsWith("\n\n"); // split on the boundary, not mid-run
    }

    private KnowledgeFile makeFile(String name, int textLength) {
        KnowledgeFile kf = KnowledgeFile.create("avatar-1", "user-1", name,
                "key/" + name, KnowledgeFile.UploadType.PDF);
        kf.setExtractedText("x".repeat(textLength));
        kf.markReady(1);
        return kf;
    }
}
