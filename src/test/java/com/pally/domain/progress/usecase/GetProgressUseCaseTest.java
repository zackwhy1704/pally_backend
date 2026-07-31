package com.pally.domain.progress.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/// Proves preferredLocale carries through GetProgressUseCase into
/// ProgressSummary — the "read once, thread the value" fix: the User this
/// use case already loads is the SAME row a naive fix would have re-queried
/// from ProgressService one layer up.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetProgressUseCaseTest {

    @Mock UserRepository userRepository;
    @Mock FlashcardRepository flashcardRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock ActivityLogService activityLogService;
    @Mock BadgeService badgeService;

    @InjectMocks GetProgressUseCase useCase;

    private static final String USER = "user-1";

    private User userWith(String locale) {
        var u = new User();
        u.setId(USER);
        u.setPreferredLocale(locale);
        return u;
    }

    private void stubCommon(User user) {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        when(avatarRepository.findByUserId(USER)).thenReturn(List.of());
        when(quizResultRepo.countDistinctDaysByUserId(USER)).thenReturn(0L);
        when(quizResultRepo.averageAccuracyByUserId(USER)).thenReturn(0.0);
        when(activityLogService.minutesPerDayLast7(USER)).thenReturn(List.of());
        when(badgeService.getBadges(USER)).thenReturn(List.of());
    }

    @Test
    void defaultLocale_carriesEnIntoSummary() {
        stubCommon(userWith("en"));

        var summary = useCase.execute(USER);

        assertThat(summary.preferredLocale()).isEqualTo("en");
    }

    @Test
    void zhLocale_carriesZhIntoSummary() {
        stubCommon(userWith("zh"));

        var summary = useCase.execute(USER);

        assertThat(summary.preferredLocale()).isEqualTo("zh");
    }
}
