package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetDailyQuizUseCase {

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final QuizGeneratorPort quizGeneratorPort;

    public List<QuizQuestion> execute(String avatarId, String userId) {
        if (!avatarRepository.existsByIdAndUserId(avatarId, userId)) {
            throw new com.pally.shared.exception.AvatarNotFoundException(avatarId);
        }

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        if (pages.isEmpty()) {
            return List.of();
        }

        return quizGeneratorPort.generate(avatarId, pages);
    }
}
