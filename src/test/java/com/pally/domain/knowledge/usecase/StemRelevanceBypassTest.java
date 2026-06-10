package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the STEM relevance bypass logic: image uploads for MATHS/SCIENCE/CODING
 * subjects should skip the relevance check because OCR garbles math notation.
 */
class StemRelevanceBypassTest {

    @Test
    void isStemSubject_maths_returnsTrue() {
        assertThat(UploadFileUseCase.isStemSubject(Subject.MATHS)).isTrue();
    }

    @Test
    void isStemSubject_science_returnsTrue() {
        assertThat(UploadFileUseCase.isStemSubject(Subject.SCIENCE)).isTrue();
    }

    @Test
    void isStemSubject_coding_returnsTrue() {
        assertThat(UploadFileUseCase.isStemSubject(Subject.CODING)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Subject.class, names = {"ENGLISH", "HISTORY", "ART", "GEOGRAPHY",
            "LANGUAGES", "MUSIC", "PHYSICAL_EDUCATION", "HEALTH", "LITERATURE", "GENERAL"})
    void isStemSubject_nonStemSubjects_returnsFalse(Subject subject) {
        assertThat(UploadFileUseCase.isStemSubject(subject)).isFalse();
    }
}
