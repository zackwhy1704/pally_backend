package com.pally.infrastructure.persistence.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.quiz.DuplicateSubmissionException;
import com.pally.domain.quiz.QuizResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/** The adapter maps the DB unique-constraint collision to the domain
 * DuplicateSubmissionException, and round-trips the stored QuizResult. */
@ExtendWith(MockitoExtension.class)
class QuizIdempotencyRepositoryAdapterTest {

    @Mock QuizSubmissionIdempotencyJpaRepository repo;
    QuizIdempotencyRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new QuizIdempotencyRepositoryAdapter(repo, new ObjectMapper());
    }

    private QuizResult result() {
        return new QuizResult("s1", 3, 4, 20, 5, false, 2);
    }

    @Test
    void claim_onUniqueConflict_throwsDuplicateSubmission() {
        when(repo.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("uq_quiz_idem_user_key"));

        assertThatThrownBy(() -> adapter.claim("u1", "k1"))
                .isInstanceOf(DuplicateSubmissionException.class);
    }

    @Test
    void storeResult_serializesResultJsonOntoTheClaimedRow() {
        var row = new QuizSubmissionIdempotencyJpaEntity();
        row.setUserId("u1");
        row.setIdempotencyKey("k1");
        when(repo.findByUserIdAndIdempotencyKey("u1", "k1")).thenReturn(Optional.of(row));

        adapter.storeResult("u1", "k1", result());

        var captor = ArgumentCaptor.forClass(QuizSubmissionIdempotencyJpaEntity.class);
        org.mockito.Mockito.verify(repo).save(captor.capture());
        assertThat(captor.getValue().getResultJson()).contains("\"xpEarned\":20");
    }

    @Test
    void findResult_returnsDeserialized_whenPresent_elseEmpty() throws Exception {
        var stored = new QuizSubmissionIdempotencyJpaEntity();
        stored.setResultJson(new ObjectMapper().writeValueAsString(result()));
        when(repo.findByUserIdAndIdempotencyKey("u1", "k1")).thenReturn(Optional.of(stored));

        assertThat(adapter.findResult("u1", "k1")).get()
                .extracting(QuizResult::xpEarned).isEqualTo(20);

        // A claimed-but-not-completed row (null result) is NOT a completed result.
        var claimOnly = new QuizSubmissionIdempotencyJpaEntity();
        when(repo.findByUserIdAndIdempotencyKey("u1", "k2")).thenReturn(Optional.of(claimOnly));
        assertThat(adapter.findResult("u1", "k2")).isEmpty();
    }
}
