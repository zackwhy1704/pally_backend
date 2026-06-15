package com.pally.api.parent;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.star.StarAwardLogJpaEntity;
import com.pally.infrastructure.persistence.star.StarAwardLogJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParentChildService} — the parent-of-child guard and the
 * star-award caps (per-award + daily). Logic moved here from the controller in
 * the controller→service refactor; the full request flow is also exercised by
 * ParentChildIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParentChildServiceTest {

    @Mock UserJpaRepository userRepo;
    @Mock com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository activityRepo;
    @Mock com.pally.domain.progress.ActivityLogService activityLogService;
    @Mock com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository quizResultRepo;
    @Mock com.pally.domain.avatar.AvatarRepository avatarRepository;
    @Mock com.pally.domain.knowledge.WikiRepository wikiRepository;
    @Mock com.pally.domain.progress.WeeklyReportService weeklyReportService;
    @Mock com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository assignmentRepo;
    @Mock StarAwardLogJpaRepository starAwardLogRepo;
    @Mock com.pally.infrastructure.persistence.module.LearningModuleJpaRepository moduleRepo;
    @Mock com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository consentRecordRepo;

    ParentChildService service;

    private static final String PARENT = "parent-1";
    private static final String CHILD = "child-1";

    @BeforeEach
    void setUp() {
        service = new ParentChildService(
                userRepo, activityRepo, activityLogService, quizResultRepo, avatarRepository,
                wikiRepository, weeklyReportService, assignmentRepo, starAwardLogRepo,
                moduleRepo, consentRecordRepo);
    }

    private UserJpaEntity child(int stars) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(CHILD);
        u.setParentId(PARENT);
        u.setStars(stars);
        return u;
    }

    @Test
    void awardStars_validAward_addsToBalanceAndLogs() {
        when(userRepo.findById(CHILD)).thenReturn(Optional.of(child(10)));
        when(starAwardLogRepo.sumAmountByParentIdSince(anyString(), any())).thenReturn(0);

        Map<String, Object> result = service.awardStars(PARENT, CHILD, Map.of("amount", 50));

        assertThat(result.get("newBalance")).isEqualTo(60);
        assertThat(result.get("awarded")).isEqualTo(50);
        verify(starAwardLogRepo).save(any(StarAwardLogJpaEntity.class));
        verify(userRepo).save(any(UserJpaEntity.class));
    }

    @Test
    void awardStars_overPerAwardCap_isRejected_noSave() {
        when(userRepo.findById(CHILD)).thenReturn(Optional.of(child(0)));

        assertThatThrownBy(() -> service.awardStars(PARENT, CHILD, Map.of("amount", 101)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Maximum");
        verify(starAwardLogRepo, never()).save(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    void awardStars_exceedingDailyCap_isRejected_noSave() {
        when(userRepo.findById(CHILD)).thenReturn(Optional.of(child(0)));
        // 280 already awarded today + 50 = 330 > 300 daily cap.
        when(starAwardLogRepo.sumAmountByParentIdSince(anyString(), any())).thenReturn(280);

        assertThatThrownBy(() -> service.awardStars(PARENT, CHILD, Map.of("amount", 50)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Daily award cap");
        verify(starAwardLogRepo, never()).save(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    void awardStars_childOfDifferentParent_isForbidden() {
        UserJpaEntity foreign = child(0);
        foreign.setParentId("someone-else");
        when(userRepo.findById(CHILD)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.awardStars(PARENT, CHILD, Map.of("amount", 10)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Access denied");
        verify(userRepo, never()).save(any());
    }

    @Test
    void assignRevision_missingModuleIds_isRejected() {
        when(userRepo.findById(CHILD)).thenReturn(Optional.of(child(0)));

        assertThatThrownBy(() ->
                service.assignRevision(PARENT, CHILD, Map.of("dueDate", "2026-01-01T00:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("moduleIds is required");
        verify(assignmentRepo, never()).save(any());
    }

    @Test
    void getDashboard_unknownChild_is404() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDashboard(PARENT, "ghost"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Child not found");
    }
}
