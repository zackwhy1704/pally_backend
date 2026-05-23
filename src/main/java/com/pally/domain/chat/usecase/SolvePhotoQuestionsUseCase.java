package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.PhotoQuestionResponse;
import com.pally.api.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SolvePhotoQuestionsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SolvePhotoQuestionsUseCase.class);
    private static final int XP_PER_QUESTION = 5;

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final PhotoQuestionPort photoQuestionPort;

    public PhotoQuestionResponse execute(String avatarId, String userId, List<String> questions) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> wikiPages = wikiRepository.findByAvatarId(avatarId);

        log.debug("Solving {} photo questions for avatarId={}", questions.size(), avatarId);

        List<QuestionAnswerDto> answers = photoQuestionPort.solveQuestions(
                avatar, wikiPages, questions
        );

        int xpEarned = questions.size() * XP_PER_QUESTION;
        String sourceWikiPage = wikiPages.isEmpty() ? null : wikiPages.getFirst().getSlug();

        return new PhotoQuestionResponse(answers, xpEarned, sourceWikiPage);
    }
}
