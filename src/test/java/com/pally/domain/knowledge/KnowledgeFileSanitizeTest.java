package com.pally.domain.knowledge;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A KnowledgeFile must never carry a NUL / control char into its extracted_text —
 * that byte is what 400'd a CJK-worksheet upload (Postgres SQLState 22021). This
 * pins the domain-boundary sanitize on BOTH writers: the setter (parent + user
 * review edit) AND the constructor (chunks are built via createChunk, not the
 * setter, so the setter alone would miss them).
 */
class KnowledgeFileSanitizeTest {

    private static final String NUL = String.valueOf((char) 0x00);

    private KnowledgeFile pdfParent() {
        return KnowledgeFile.reconstitute("id", "av", "u", "worksheet.pdf",
                "key", 1, KnowledgeFile.UploadType.PDF,
                KnowledgeFile.Status.READY, Instant.now());
    }

    @Test
    void setterStripsNulFromExtractedText() {
        KnowledgeFile f = pdfParent();
        f.setExtractedText("218" + NUL + "号巴士");
        assertThat(f.getExtractedText()).isEqualTo("218号巴士");
    }

    @Test
    void reconstituteWithTextStripsNul() {
        KnowledgeFile f = KnowledgeFile.reconstitute("id", "av", "u", "w.pdf",
                "key", 1, KnowledgeFile.UploadType.PDF,
                KnowledgeFile.Status.READY, Instant.now(), "两百" + NUL + "个");
        assertThat(f.getExtractedText()).isEqualTo("两百个");
    }

    @Test
    void chunkConstructorStripsNul() {
        // The path the setter alone would MISS — chunks route through the constructor.
        KnowledgeFile chunk = KnowledgeFile.createChunk(
                pdfParent(), "chapter 1", 1, 2, 2, "第四十七座" + NUL + "组屋");
        assertThat(chunk.getExtractedText()).isEqualTo("第四十七座组屋");
    }
}
