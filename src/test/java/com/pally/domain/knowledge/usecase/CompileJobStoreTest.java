package com.pally.domain.knowledge.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CompileJobStore — in-memory job state tracking for async compiles.
 * Verifies: put/get, findByAvatarId, eviction of expired jobs.
 */
class CompileJobStoreTest {

    private CompileJobStore store;

    @BeforeEach
    void setUp() {
        store = new CompileJobStore();
    }

    @Test
    void putAndGet_returnsStoredJob() {
        CompileJobStore.JobStatus job = new CompileJobStore.JobStatus(
                "job-1", "avatar-1", CompileJobStore.JobState.RUNNING,
                0, 0, null, null, Instant.now());

        store.put("job-1", job);

        assertThat(store.get("job-1")).isNotNull();
        assertThat(store.get("job-1").jobId()).isEqualTo("job-1");
        assertThat(store.get("job-1").state()).isEqualTo(CompileJobStore.JobState.RUNNING);
    }

    @Test
    void get_unknownJob_returnsNull() {
        assertThat(store.get("nonexistent")).isNull();
    }

    @Test
    void findByAvatarId_returnsLatestJob() {
        CompileJobStore.JobStatus older = new CompileJobStore.JobStatus(
                "job-old", "avatar-1", CompileJobStore.JobState.DONE,
                3, 3, "tier1", null, Instant.now().minusSeconds(600));
        CompileJobStore.JobStatus newer = new CompileJobStore.JobStatus(
                "job-new", "avatar-1", CompileJobStore.JobState.RUNNING,
                0, 0, null, null, Instant.now());

        store.put("job-old", older);
        store.put("job-new", newer);

        CompileJobStore.JobStatus found = store.findByAvatarId("avatar-1");
        assertThat(found).isNotNull();
        assertThat(found.jobId()).isEqualTo("job-new");
    }

    @Test
    void findByAvatarId_noMatch_returnsNull() {
        assertThat(store.findByAvatarId("avatar-unknown")).isNull();
    }

    @Test
    void withDone_updatesStateAndClearsError() {
        CompileJobStore.JobStatus running = new CompileJobStore.JobStatus(
                "job-1", "avatar-1", CompileJobStore.JobState.RUNNING,
                0, 0, null, null, Instant.now());

        CompileJobStore.JobStatus done = running.withDone(5, 5, "gemini-tier1");

        assertThat(done.state()).isEqualTo(CompileJobStore.JobState.DONE);
        assertThat(done.pagesCompiled()).isEqualTo(5);
        assertThat(done.compiledBy()).isEqualTo("gemini-tier1");
        assertThat(done.errorMessage()).isNull();
    }

    @Test
    void withFailed_setsErrorMessage() {
        CompileJobStore.JobStatus running = new CompileJobStore.JobStatus(
                "job-1", "avatar-1", CompileJobStore.JobState.RUNNING,
                2, 5, "gemini-tier1", null, Instant.now());

        CompileJobStore.JobStatus failed = running.withFailed("Claude API timeout");

        assertThat(failed.state()).isEqualTo(CompileJobStore.JobState.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Claude API timeout");
        assertThat(failed.pagesCompiled()).isEqualTo(2); // partial progress preserved
    }

    @Test
    void evictExpired_removesOldJobs_keepsRecent() {
        CompileJobStore.JobStatus old = new CompileJobStore.JobStatus(
                "old", "avatar-1", CompileJobStore.JobState.DONE,
                3, 3, "tier1", null, Instant.now().minusSeconds(3600)); // 1 hour ago
        CompileJobStore.JobStatus recent = new CompileJobStore.JobStatus(
                "recent", "avatar-2", CompileJobStore.JobState.RUNNING,
                0, 0, null, null, Instant.now());

        store.put("old", old);
        store.put("recent", recent);

        store.evictExpired();

        assertThat(store.get("old")).isNull();
        assertThat(store.get("recent")).isNotNull();
    }

    @Test
    void evictExpired_nothingToEvict_doesNotFail() {
        store.evictExpired(); // no-op on empty store
        assertThat(store.getJobs()).isEmpty();
    }

    @Test
    void withProgress_updatesCountsAndTier() {
        CompileJobStore.JobStatus running = new CompileJobStore.JobStatus(
                "job-1", "avatar-1", CompileJobStore.JobState.RUNNING,
                0, 0, null, null, Instant.now());

        CompileJobStore.JobStatus updated = running.withProgress(3, 5, "gemini-tier2");

        assertThat(updated.pagesCompiled()).isEqualTo(3);
        assertThat(updated.pagesTotal()).isEqualTo(5);
        assertThat(updated.compiledBy()).isEqualTo("gemini-tier2");
        assertThat(updated.state()).isEqualTo(CompileJobStore.JobState.RUNNING);
    }
}
