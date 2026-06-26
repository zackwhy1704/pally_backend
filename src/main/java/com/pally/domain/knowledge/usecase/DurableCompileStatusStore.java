package com.pally.domain.knowledge.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.persistence.knowledge.CompileStatusJpaEntity;
import com.pally.infrastructure.persistence.knowledge.CompileStatusJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable (DB-backed) per-avatar compile status (C2). Mirrors the latest compile
 * outcome that {@link CompileJobStore} holds in memory, so GET /wiki/compile/status
 * is correct on ANY Railway replica — a second instance no longer returns "nothing"
 * for a partial compile and lets the web show "✓ compiled".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DurableCompileStatusStore {

    private final CompileStatusJpaRepository repo;
    private final ObjectMapper objectMapper;

    /** Upsert the latest outcome for an avatar. */
    @Transactional
    public void record(String avatarId, String state, int compiled, int total,
                       List<FailedPage> failed) {
        CompileStatusJpaEntity e = repo.findById(avatarId).orElseGet(CompileStatusJpaEntity::new);
        e.setAvatarId(avatarId);
        e.setState(state);
        e.setPagesCompiled(compiled);
        e.setPagesTotal(total);
        e.setPagesFailed(failed == null ? 0 : failed.size());
        e.setFailedPages(toJson(failed));
        e.setUpdatedAt(Instant.now());
        repo.save(e);
    }

    @Transactional(readOnly = true)
    public Optional<DurableStatus> find(String avatarId) {
        return repo.findById(avatarId).map(e -> new DurableStatus(
                e.getState(), e.getPagesCompiled(), e.getPagesTotal(),
                e.getPagesFailed(), fromJson(e.getFailedPages())));
    }

    public record DurableStatus(String state, int pagesCompiled, int pagesTotal,
                                int pagesFailed, List<FailedPage> failedPages) {}

    private String toJson(List<FailedPage> failed) {
        if (failed == null || failed.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(failed);
        } catch (Exception e) {
            log.warn("[CompileStatus] failedPages serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private List<FailedPage> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
