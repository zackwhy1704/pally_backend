package com.pally.domain.quiz.port;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.QuizQuestion;

import java.util.List;

public interface QuizGeneratorPort {
    /**
     * @param contentLanguage the language the quiz should be generated in ('en' | 'zh') — V124.
     *                        Resolved by the caller from the (source) avatar; the impl appends a
     *                        PromptLanguage directive (empty for 'en' → byte-identical English).
     */
    List<QuizQuestion> generate(String avatarId, List<WikiPage> pages, String contentLanguage);
}
