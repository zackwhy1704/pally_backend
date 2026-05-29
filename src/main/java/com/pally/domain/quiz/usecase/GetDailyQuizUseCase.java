package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetDailyQuizUseCase {

    /// Cap on pages fed to the quiz generator per request. Five questions ~=
    /// five pages keeps the prompt small enough for Haiku and forces the
    /// "prioritise weak material" bias to actually matter.
    private static final int MAX_PAGES_PER_QUIZ = 5;

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final QuizGeneratorPort quizGeneratorPort;

    public List<QuizQuestion> execute(String avatarId, String userId) {
        if (!avatarRepository.existsByIdAndUserId(avatarId, userId)) {
            throw new com.pally.shared.exception.AvatarNotFoundException(avatarId);
        }

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId).stream()
                .filter(p -> p.getStatus() == WikiPage.Status.ACTIVE)
                .toList();
        if (pages.isEmpty()) {
            return List.of();
        }

        // R3 — bias toward the student's weak spots and under-tested material.
        // Priority: lowest certainty first, ties broken by lowest quiz-use
        // count. Forces fresh material into rotation instead of always
        // re-quizzing the same handful of pages.
        List<WikiPage> prioritised = pages.stream()
                .sorted(Comparator
                        .comparingDouble(WikiPage::getCertaintyScore)
                        .thenComparingInt(WikiPage::getQuizUseCount))
                .limit(MAX_PAGES_PER_QUIZ)
                .toList();

        // Record that these pages seeded a quiz so coverage stays balanced
        // even if the student keeps acing the same topic.
        wikiRepository.recordQuizUsage(avatarId,
                prioritised.stream().map(WikiPage::getSlug).toList());

        return quizGeneratorPort.generate(avatarId, prioritised);
    }
}
