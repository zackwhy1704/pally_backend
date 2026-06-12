package com.pally.domain.assignment;

import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaRepository;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Part A2 guards. The single most important invariant: model answers are
 * server-withheld until {@code answersReleasedAt <= now}. The pre-release
 * student fetch must contain NO model-answer key at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssignmentModelAnswerTest {

    @Mock AssignmentJpaRepository assignmentRepo;
    @Mock AssignmentCompletionJpaRepository completionRepo;
    @Mock LearningModuleJpaRepository moduleRepo;

    @InjectMocks AssignmentService service;

    private static final String AID = "assign-1";
    private static final String STUDENT = "student-1";

    private AssignmentJpaEntity assignment() {
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId(AID);
        a.setClassId("class-1");
        a.setTitle("Photosynthesis worksheet");
        a.setType(AssignmentJpaEntity.TYPE_POST_CLASS);
        a.setDueDate(Instant.now().plus(2, ChronoUnit.DAYS));
        a.setCreatedBy("teacher-1");
        a.setCreatedAt(Instant.now());
        a.setModelAnswer("{\"q1\":\"Sunlight, water, CO2\"}");
        return a;
    }

    @Test
    void studentDetail_beforeRelease_omitsModelAnswerKeyEntirely() {
        AssignmentJpaEntity a = assignment(); // answersReleasedAt == null
        when(assignmentRepo.findById(AID)).thenReturn(Optional.of(a));
        when(completionRepo.findByAssignmentIdAndUserId(AID, STUDENT)).thenReturn(Optional.empty());

        Map<String, Object> detail = service.getStudentDetail(AID, STUDENT);

        assertThat(detail).doesNotContainKey("modelAnswer");
        assertThat(detail).containsEntry("answersReleased", false);
    }

    @Test
    void studentDetail_withFutureRelease_stillOmitsModelAnswer() {
        AssignmentJpaEntity a = assignment();
        a.setAnswersReleasedAt(Instant.now().plus(1, ChronoUnit.DAYS)); // future
        when(assignmentRepo.findById(AID)).thenReturn(Optional.of(a));
        when(completionRepo.findByAssignmentIdAndUserId(AID, STUDENT)).thenReturn(Optional.empty());

        Map<String, Object> detail = service.getStudentDetail(AID, STUDENT);

        assertThat(detail).doesNotContainKey("modelAnswer");
        assertThat(detail).containsEntry("answersReleased", false);
    }

    @Test
    void studentDetail_afterRelease_includesModelAnswer() {
        AssignmentJpaEntity a = assignment();
        a.setAnswersReleasedAt(Instant.now().minus(1, ChronoUnit.MINUTES)); // past
        when(assignmentRepo.findById(AID)).thenReturn(Optional.of(a));
        when(completionRepo.findByAssignmentIdAndUserId(AID, STUDENT)).thenReturn(Optional.empty());

        Map<String, Object> detail = service.getStudentDetail(AID, STUDENT);

        assertThat(detail).containsEntry("answersReleased", true);
        assertThat(detail).containsEntry("modelAnswer", "{\"q1\":\"Sunlight, water, CO2\"}");
    }

    @Test
    void releaseAnswers_now_setsReleaseInPast_andMarksReleased() {
        AssignmentJpaEntity a = assignment();
        when(assignmentRepo.findById(AID)).thenReturn(Optional.of(a));
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.releaseAnswers(AID, Instant.now());

        assertThat(result.answersReleased()).isTrue();
        assertThat(result.getAnswersReleasedAt()).isNotNull();
    }

    @Test
    void releaseAnswers_defaultsToDueDate_whenNull() {
        AssignmentJpaEntity a = assignment();
        when(assignmentRepo.findById(AID)).thenReturn(Optional.of(a));
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.releaseAnswers(AID, null);

        assertThat(result.getAnswersReleasedAt()).isEqualTo(a.getDueDate());
    }

    @Test
    void setModelAnswer_persistsBlob_withoutReleasing() {
        AssignmentJpaEntity a = assignment();
        a.setModelAnswer(null);
        when(assignmentRepo.findById(AID)).thenReturn(Optional.of(a));
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.setModelAnswer(AID, "{\"q1\":\"42\"}");

        assertThat(result.getModelAnswer()).isEqualTo("{\"q1\":\"42\"}");
        assertThat(result.answersReleased()).isFalse();
    }
}
