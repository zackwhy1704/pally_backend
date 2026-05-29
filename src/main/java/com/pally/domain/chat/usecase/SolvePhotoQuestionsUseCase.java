package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.PhotoQuestionResponse;
import com.pally.api.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.XpService;
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
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;
    private final XpService xpService;

    public PhotoQuestionResponse execute(String avatarId, String userId, List<String> questions) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> wikiPages = wikiRepository.findByAvatarId(avatarId);

        log.debug("Solving {} photo questions for avatarId={}", questions.size(), avatarId);

        List<QuestionAnswerDto> answers = photoQuestionPort.solveQuestions(
                avatar, wikiPages, questions
        );

        int baseXp = questions.size() * XP_PER_QUESTION;

        // Route through XpService for per-avatar daily decay + L10 star
        // multiplier. Spamming the same homework photo decays the reward;
        // the first photo of the day always pays full XP.
        var award = xpService.awardForPhoto(userId, avatarId, baseXp);
        int xpEarned = award.xpGranted();

        activityLogService.log(userId, avatarId, ActivityLogService.TYPE_PHOTO, 0, xpEarned);
        badgeService.checkAndGrantMilestones(userId);

        String sourceWikiPage = wikiPages.isEmpty() ? null : wikiPages.getFirst().getSlug();

        return new PhotoQuestionResponse(answers, xpEarned, sourceWikiPage,
                award.creditResult().levelledUp(), award.creditResult().newLevel());
    }
}
