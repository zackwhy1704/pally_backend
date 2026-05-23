package com.pally.domain.quiz.port;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.QuizQuestion;

import java.util.List;

public interface QuizGeneratorPort {
    List<QuizQuestion> generate(String avatarId, List<WikiPage> pages);
}
