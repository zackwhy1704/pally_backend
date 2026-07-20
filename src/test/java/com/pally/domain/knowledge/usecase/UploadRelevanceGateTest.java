package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile.UploadType;
import com.pally.domain.knowledge.RelevanceScore;
import org.junit.jupiter.api.Test;

import static com.pally.domain.knowledge.usecase.UploadFileUseCase.shouldRejectRelevance;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 relevance gate — the pure decision extracted from {@link UploadFileUseCase}.
 * These pin the QA-1.2 receipt false-accept fix and its two root holes.
 */
class UploadRelevanceGateTest {

    // A receipt PHOTO on a STEM subject was the prod false-accept. Two holes:
    // (1) STEM photos skipped relevance entirely; (2) topic-bounded subjects
    // ignored studyMaterial. studyMaterial=false must now reject regardless.
    @Test
    void receiptPhotoOnStemSubject_isRejected() {
        RelevanceScore receipt = new RelevanceScore(0.9, "A receipt", false); // high score, not study material
        assertThat(shouldRejectRelevance(Subject.SCIENCE, UploadType.PHOTO, receipt)).isTrue();
        assertThat(shouldRejectRelevance(Subject.MATHS, UploadType.PHOTO, receipt)).isTrue();
    }

    // The whole reason STEM photos skipped relevance: OCR garbles math so the topic
    // score is unreliable. A legit homework photo (studyMaterial=true) with a LOW
    // score must still be ACCEPTED — only its studyMaterial verdict is enforced.
    @Test
    void legitStemHomeworkPhoto_lowGarbledScore_isAccepted() {
        RelevanceScore garbledButReal = new RelevanceScore(0.1, "hard to read but math", true);
        assertThat(shouldRejectRelevance(Subject.MATHS, UploadType.PHOTO, garbledButReal)).isFalse();
        assertThat(shouldRejectRelevance(Subject.CODING, UploadType.PHOTO, garbledButReal)).isFalse();
    }

    // Hole (2): a non-study-material PDF on a topic-bounded subject was previously
    // accepted (that path gated on the topic score alone, ignoring studyMaterial).
    @Test
    void nonStudyMaterialPdfOnTopicBoundedSubject_isRejected_evenWithHighScore() {
        RelevanceScore receiptPdf = new RelevanceScore(0.8, "An invoice", false);
        assertThat(shouldRejectRelevance(Subject.HISTORY, UploadType.PDF, receiptPdf)).isTrue();
    }

    @Test
    void offTopicStudyMaterialOnTopicBoundedNonPhoto_isRejectedViaScore() {
        RelevanceScore offTopic = new RelevanceScore(0.1, "music theory notes", true);
        assertThat(shouldRejectRelevance(Subject.MATHS, UploadType.PDF, offTopic)).isTrue();
    }

    @Test
    void onTopicStudyMaterial_isAccepted() {
        RelevanceScore onTopic = new RelevanceScore(0.9, "algebra notes", true);
        assertThat(shouldRejectRelevance(Subject.MATHS, UploadType.PDF, onTopic)).isFalse();
    }

    // GENERAL (unbounded): no topic to be off-topic from, so it gates ONLY on
    // studyMaterial — a receipt is rejected, low-score study material is accepted.
    @Test
    void generalSubject_gatesOnStudyMaterialOnly() {
        assertThat(shouldRejectRelevance(Subject.GENERAL, UploadType.TEXT,
                new RelevanceScore(0.9, "a receipt", false))).isTrue();
        assertThat(shouldRejectRelevance(Subject.GENERAL, UploadType.PDF,
                new RelevanceScore(0.05, "obscure but real notes", true))).isFalse();
    }
}
