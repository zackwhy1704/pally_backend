package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.subscription.ChunkCompileGuard;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Picks ONE chapter chunk to compile. This is the paid action of the chunked flow:
 * it checks the {@link ChunkCompileGuard} (402 when over allowance), flips the child
 * PENDING_CHUNK → READY so the normal per-avatar compile sweep takes it, and triggers
 * the debounce scheduler. Nothing else changes — the chunk compiles through the exact
 * existing pipeline as an ordinary READY file.
 *
 * <p>IDEMPOTENT + never double-charges:
 * <ul>
 *   <li>PENDING_CHUNK → guard-check, pick, schedule (the one charged path; charged on
 *       SUCCESS via compiled_at, so this 402 gate reads past successes).</li>
 *   <li>READY but not yet compiled (a prior attempt failed) → re-schedule, NO re-charge.</li>
 *   <li>already compiled → no-op.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CompileChunkUseCase {

    private final KnowledgeRepository knowledgeRepository;
    private final AvatarRepository avatarRepository;
    private final ChunkCompileGuard chunkCompileGuard;
    private final WikiRecompileScheduler recompileScheduler;
    private final ConsentGuard consentGuard;
    private final AvatarSlotGuard avatarSlotGuard;

    public record Result(String chunkId, String status, ChunkCompileGuard.ChunkAllowance allowance) {}

    @Transactional
    public Result execute(String userId, String avatarId, String chunkId) {
        // Ownership — avatars are user-scoped.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        KnowledgeFile chunk = knowledgeRepository.findById(chunkId)
                .filter(KnowledgeFile::isChunk)
                .filter(c -> c.getAvatarId().equals(avatarId))
                .orElseThrow(() -> new BusinessException("Chapter not found", 404));

        // Child-data ingress + AI-transfer consent + slot lock — the SAME gate stack as a
        // compile-triggering upload (UploadFileUseCase). Compiling a chunk ships the child's
        // notes to Gemini overseas, so requireChildDataIngressConsent (VERIFIED parental
        // approval for under-13) MUST precede requireAiConsent — the latter alone is satisfied
        // by a child's own self-grant. Invariant: every child-data ingress calls
        // requireChildDataIngressConsent at entry (ConsentGuard javadoc); pinned by
        // ChildDataIngressGuardInvariantTest.
        consentGuard.requireChildDataIngressConsent(userId);
        consentGuard.requireAiConsent(userId);
        avatarSlotGuard.requireActive(avatarId, userId);

        if (chunk.getStatus() == KnowledgeFile.Status.PENDING_CHUNK) {
            chunkCompileGuard.requireChunkCompileQuota(userId);   // 402 CHUNK_COMPILE if over
            chunk.markPicked();
            knowledgeRepository.save(chunk);
            recompileScheduler.requestRecompile(avatarId);
            log.info("[ChunkCompile] picked chunk={} avatar={} — compile scheduled", chunkId, avatarId);
        } else if (chunk.getStatus() == KnowledgeFile.Status.READY && chunk.getCompiledBy() == null) {
            // Picked before but not yet compiled (e.g. a failed attempt) — retry, no re-charge.
            recompileScheduler.requestRecompile(avatarId);
            log.info("[ChunkCompile] re-triggering already-picked chunk={} avatar={}", chunkId, avatarId);
        } else {
            log.info("[ChunkCompile] chunk={} already compiled — no-op", chunkId);
        }

        return new Result(chunkId, chunk.getStatus().name(), chunkCompileGuard.allowance(userId));
    }
}
