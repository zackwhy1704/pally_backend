package com.pally.domain.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chapter-chunk domain invariants. A child chunk must start compile-IGNORED
 * (PENDING_CHUNK, compiled_by null) so the executeBatched sweep can't compile it
 * eagerly; picking flips it to READY; and it must carry its OWN text slice so the
 * dedup gate (which hashes extracted text) never collides siblings.
 */
class KnowledgeFileChunkTest {

    private KnowledgeFile parent() {
        KnowledgeFile p = KnowledgeFile.create("av1", "u1", "textbook.pdf", "k/textbook.pdf",
                KnowledgeFile.UploadType.PDF);
        p.setExtractedText("CHAPTER ONE ... CHAPTER TWO ...");
        return p;
    }

    @Test
    void createChunk_startsPendingChunk_notReady_soTheSweepIgnoresIt() {
        KnowledgeFile parent = parent();
        KnowledgeFile chunk = KnowledgeFile.createChunk(parent, "Chapter 1", 1, 20, 20, "CHAPTER ONE ...");

        assertThat(chunk.getStatus()).isEqualTo(KnowledgeFile.Status.PENDING_CHUNK);
        assertThat(chunk.getCompiledBy()).isNull();       // never swept until picked
        assertThat(chunk.isChunk()).isTrue();
        assertThat(chunk.getParentFileId()).isEqualTo(parent.getId());
        assertThat(chunk.getChunkTitle()).isEqualTo("Chapter 1");
        assertThat(chunk.getPageFrom()).isEqualTo(1);
        assertThat(chunk.getPageTo()).isEqualTo(20);
        assertThat(chunk.getAvatarId()).isEqualTo(parent.getAvatarId());
    }

    @Test
    void siblingChunks_carryDistinctText_soDedupCannotCollideThem() {
        KnowledgeFile parent = parent();
        KnowledgeFile c1 = KnowledgeFile.createChunk(parent, "Chapter 1", 1, 20, 20, "CHAPTER ONE unique text");
        KnowledgeFile c2 = KnowledgeFile.createChunk(parent, "Chapter 2", 21, 40, 20, "CHAPTER TWO different text");

        assertThat(c1.getExtractedText()).isNotEqualTo(c2.getExtractedText());
        // each chunk holds its own slice, NOT the full parent text
        assertThat(c1.getExtractedText()).isNotEqualTo(parent.getExtractedText());
    }

    @Test
    void markPicked_flipsToReady_soTheNormalCompileSweepTakesIt() {
        KnowledgeFile chunk = KnowledgeFile.createChunk(parent(), "Chapter 1", 1, 20, 20, "text");
        chunk.markPicked();
        assertThat(chunk.getStatus()).isEqualTo(KnowledgeFile.Status.READY);
        assertThat(chunk.getCompiledBy()).isNull(); // READY but not yet compiled
    }

    @Test
    void markCompiled_stampsBothMarkerAndTimestamp_theAllowanceAnchor() {
        KnowledgeFile chunk = KnowledgeFile.createChunk(parent(), "Chapter 1", 1, 20, 20, "text");
        assertThat(chunk.getCompiledAt()).isNull();

        chunk.markCompiled("gemini-2.5-flash");

        assertThat(chunk.getCompiledBy()).isEqualTo("gemini-2.5-flash");
        assertThat(chunk.getCompiledAt()).isNotNull(); // enables the rolling-window count
    }

    @Test
    void markSegmented_movesParentOutOfTheCompileSweep() {
        KnowledgeFile parent = parent();
        parent.markSegmented();
        assertThat(parent.getStatus()).isEqualTo(KnowledgeFile.Status.SEGMENTED);
        assertThat(parent.isChunk()).isFalse(); // parent is not itself a chunk
    }
}
