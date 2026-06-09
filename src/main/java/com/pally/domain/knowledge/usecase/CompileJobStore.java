package com.pally.domain.knowledge.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for async compile job state. Entries auto-expire after 30 minutes.
 * No new DB table needed — cleared on restart, which is fine for polling-based status.
 */
@Component
public class CompileJobStore {

    private static final Logger log = LoggerFactory.getLogger(CompileJobStore.class);
    private static final long EXPIRY_MS = 30 * 60 * 1000L; // 30 minutes

    public enum JobState { RUNNING, DONE, FAILED }

    public record JobStatus(
            String jobId,
            String avatarId,
            JobState state,
            int pagesCompiled,
            int pagesTotal,
            String compiledBy,
            String errorMessage,
            Instant createdAt
    ) {
        public JobStatus withState(JobState newState) {
            return new JobStatus(jobId, avatarId, newState, pagesCompiled, pagesTotal,
                    compiledBy, errorMessage, createdAt);
        }

        public JobStatus withProgress(int compiled, int total, String tier) {
            return new JobStatus(jobId, avatarId, state, compiled, total, tier,
                    errorMessage, createdAt);
        }

        public JobStatus withDone(int compiled, int total, String tier) {
            return new JobStatus(jobId, avatarId, JobState.DONE, compiled, total, tier,
                    null, createdAt);
        }

        public JobStatus withFailed(String error) {
            return new JobStatus(jobId, avatarId, JobState.FAILED, pagesCompiled, pagesTotal,
                    compiledBy, error, createdAt);
        }
    }

    private final ConcurrentHashMap<String, JobStatus> jobs = new ConcurrentHashMap<>();

    public void put(String jobId, JobStatus status) {
        jobs.put(jobId, status);
    }

    public JobStatus get(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Find the most recent job for an avatar (any state).
     */
    public JobStatus findByAvatarId(String avatarId) {
        return jobs.values().stream()
                .filter(j -> j.avatarId().equals(avatarId))
                .reduce((a, b) -> a.createdAt().isAfter(b.createdAt()) ? a : b)
                .orElse(null);
    }

    /**
     * Evict expired jobs every 10 minutes.
     */
    @Scheduled(fixedRate = 600_000)
    void evictExpired() {
        Instant cutoff = Instant.now().minusMillis(EXPIRY_MS);
        int before = jobs.size();
        jobs.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
        int removed = before - jobs.size();
        if (removed > 0) {
            log.debug("[CompileJobStore] Evicted {} expired job(s)", removed);
        }
    }

    // package-private for testing
    Map<String, JobStatus> getJobs() { return jobs; }
}
