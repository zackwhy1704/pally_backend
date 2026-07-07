package com.pally.integration;

import com.pally.domain.marking.MarkingCorrection;
import com.pally.domain.marking.MarkingCorrectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part 4 damper, proven against real Postgres: a REMOVED correction is excluded
 * from BOTH the recompile feed (findUncompiledByClassId) and the teacher view
 * (findActiveByClassId). This is the correctness the whole damper rests on — a
 * removed correction must never ground a future draft — and it lives in a Spring
 * Data derived query, so only a real DB proves it.
 */
class MarkingCorrectionRemovalIntegrationTest extends IntegrationTestBase {

    @Autowired MarkingCorrectionRepository repository;

    private MarkingCorrection uncompiled(String classId) {
        return MarkingCorrection.capture(
                "sub-" + System.nanoTime(), classId, "MATHS",
                "A", "C", "Good", "Wrong — check your working");
    }

    @Test
    void removedCorrection_isExcludedFromBothTheFeedAndTheView() {
        String classId = "cls-" + System.nanoTime();
        MarkingCorrection keep = repository.save(uncompiled(classId));
        MarkingCorrection drop = repository.save(uncompiled(classId));

        // Both start eligible for the recompile feed.
        assertThat(repository.findUncompiledByClassId(classId)).hasSize(2);

        repository.markRemoved(drop.id(), Instant.now());

        // The removed one is gone from the FEED — it can no longer ground a draft.
        List<MarkingCorrection> feed = repository.findUncompiledByClassId(classId);
        assertThat(feed).extracting(MarkingCorrection::id).containsExactly(keep.id());
        // ...and gone from the teacher VIEW.
        assertThat(repository.findActiveByClassId(classId))
                .extracting(MarkingCorrection::id).containsExactly(keep.id());
    }
}
