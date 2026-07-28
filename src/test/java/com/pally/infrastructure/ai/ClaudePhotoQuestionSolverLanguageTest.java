package com.pally.infrastructure.ai;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-identical-English guard for photo-question answers (Phase 1b.4b). The answer follows the
 * avatar's content_language (empty for 'en'). Only the main answer prompt is threaded — classifyVisual
 * stays English (it returns enum labels). Empty question/classification lists skip the numbering loop,
 * so buildPrompt is exercised purely.
 */
class ClaudePhotoQuestionSolverLanguageTest {

    private ClaudePhotoQuestionSolver solver() {
        return new ClaudePhotoQuestionSolver(null, null, null, null, null);
    }

    private Avatar avatar(String contentLanguage) {
        Avatar a = Avatar.create("u-1", "Mochi", Subject.MATHS, CharacterType.MOCHI);
        a.setContentLanguage(contentLanguage);
        return a;
    }

    private String promptFor(String contentLanguage) {
        return solver().buildPrompt(avatar(contentLanguage), "wiki context", List.of(), List.of());
    }

    @Test
    void englishAnswerPrompt_isByteIdenticalBase_zhIsBasePlusDirectiveOnly() {
        String en = promptFor("en");
        String zh = promptFor("zh");
        assertThat(en).doesNotContain("华语");
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }
}
