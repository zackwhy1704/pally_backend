package com.pally.domain.marking;

import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Teacher visibility + removal (Part 4). */
@ExtendWith(MockitoExtension.class)
class MarkingCorrectionServiceTest {

    @Mock MarkingCorrectionRepository repository;

    private MarkingCorrectionService service() {
        return new MarkingCorrectionService(repository);
    }

    private MarkingCorrection correction(String id, String classId, Instant compiledAt, Instant removedAt) {
        return new MarkingCorrection(id, "sub", classId, "MATHS",
                "A", "C", "Good", "Wrong, see working",
                Instant.parse("2026-07-01T00:00:00Z"), compiledAt, removedAt);
    }

    @Test
    void list_returnsDtosWithPendingVsAppliedStatus() {
        when(repository.findActiveByClassId("c1")).thenReturn(List.of(
                correction("pending", "c1", null, null),
                correction("applied", "c1", Instant.now(), null)));

        List<Map<String, Object>> out = service().listForClass("c1");

        assertThat(out).hasSize(2);
        assertThat(out.get(0).get("status")).isEqualTo("pending");
        assertThat(out.get(1).get("status")).isEqualTo("applied");
        assertThat(out.get(0)).containsKeys("aiSuggestedGrade", "teacherGrade", "aiFeedback", "teacherFeedback");
    }

    @Test
    void remove_marksTheCorrectionRemoved() {
        when(repository.findById("x")).thenReturn(Optional.of(correction("x", "c1", null, null)));
        service().remove("c1", "x");
        verify(repository).markRemoved(eq("x"), any(Instant.class));
    }

    @Test
    void remove_correctionInAnotherClass_is404_andDoesNotRemove() {
        // Cross-class IDOR guard: the correction exists but belongs to c2.
        when(repository.findById("x")).thenReturn(Optional.of(correction("x", "c2", null, null)));
        assertThatThrownBy(() -> service().remove("c1", "x"))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).markRemoved(any(), any());
    }

    @Test
    void remove_unknownCorrection_is404() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().remove("c1", "nope"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void remove_alreadyRemoved_isIdempotent_noSecondWrite() {
        when(repository.findById("x")).thenReturn(
                Optional.of(correction("x", "c1", null, Instant.now())));
        service().remove("c1", "x");
        verify(repository, never()).markRemoved(any(), any());
    }
}
