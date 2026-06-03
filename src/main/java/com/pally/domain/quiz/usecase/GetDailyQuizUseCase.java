package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetDailyQuizUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetDailyQuizUseCase.class);

    /// Cap on pages fed to the quiz generator per request. Five questions ~=
    /// five pages keeps the prompt small enough for Haiku and forces the
    /// "prioritise weak material" bias to actually matter.
    private static final int MAX_PAGES_PER_QUIZ = 5;

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final QuizGeneratorPort quizGeneratorPort;
    private final AvatarSlotGuard avatarSlotGuard;

    public List<QuizQuestion> execute(String avatarId, String userId) {
        // Fix 2: Slot guard — locked avatars cannot be quizzed.
        avatarSlotGuard.requireActive(avatarId, userId);

        List<WikiPage> allPages = wikiRepository.findByAvatarId(avatarId);
        List<WikiPage> pages = allPages.stream()
                .filter(p -> p.getStatus() == WikiPage.Status.ACTIVE)
                .toList();

        // ── Pipeline log: quiz source ─────────────────────────────────────
        log.info("[Pipeline:Quiz] avatarId={} totalPages={} activePages={}",
                avatarId, allPages.size(), pages.size());

        if (pages.isEmpty()) {
            log.warn("[Pipeline:Quiz] NO ACTIVE wiki pages for avatarId={} — " +
                     "quiz returns empty. Total pages (all statuses)={}. " +
                     "Upload notes and wait for compile, or call /wiki/recompile.",
                     avatarId, allPages.size());
            return List.of();
        }

        // R3 — bias toward the student's weak spots and under-tested material.
        List<WikiPage> prioritised = pages.stream()
                .sorted(Comparator
                        .comparingDouble(WikiPage::getCertaintyScore)
                        .thenComparingInt(WikiPage::getQuizUseCount))
                .limit(MAX_PAGES_PER_QUIZ)
                .toList();

        log.info("[Pipeline:Quiz] Generating from {} pages: slugs={}",
                prioritised.size(), prioritised.stream().map(WikiPage::getSlug).toList());

        // Record that these pages seeded a quiz so coverage stays balanced.
        wikiRepository.recordQuizUsage(avatarId,
                prioritised.stream().map(WikiPage::getSlug).toList());

        List<QuizQuestion> questions = quizGeneratorPort.generate(avatarId, prioritised);
        log.info("[Pipeline:Quiz] Generated {} questions for avatarId={}", questions.size(), avatarId);
        return questions;
    }
}
