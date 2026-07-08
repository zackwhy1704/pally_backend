package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.subscription.ChunkCompileGuard;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists an avatar's chapter chunks + the current chunk-compile allowance — the read
 * behind BOTH the post-upload picker and the locked-chapter surface. Reading the
 * allowance HERE (one source) is why the 402 body stays a bare {code, feature}: the
 * client never has to guess the counter.
 */
@Service
@RequiredArgsConstructor
public class GetChaptersUseCase {

    private final KnowledgeRepository knowledgeRepository;
    private final AvatarRepository avatarRepository;
    private final ChunkCompileGuard chunkCompileGuard;

    /** One chapter row. {@code state}: LOCKED (not compiled), COMPILING (picked, in
     *  flight), or COMPILED (done). */
    public record Chapter(String chunkId, String parentFileId, String title,
                          int pageFrom, int pageTo, int pageCount, String state) {}

    /** {@code allowanceLimit} = -1 means unlimited. */
    public record ChaptersResult(int allowanceUsed, int allowanceLimit, List<Chapter> chapters) {}

    @Transactional(readOnly = true)
    public ChaptersResult execute(String userId, String avatarId) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<Chapter> chapters = new ArrayList<>();
        for (KnowledgeFile parent : knowledgeRepository.findByAvatarId(avatarId)) {
            if (parent.getStatus() != KnowledgeFile.Status.SEGMENTED) continue;
            for (KnowledgeFile c : knowledgeRepository.findByParentFileId(parent.getId())) {
                chapters.add(new Chapter(
                        c.getId(), parent.getId(), c.getChunkTitle(),
                        c.getPageFrom() == null ? 0 : c.getPageFrom(),
                        c.getPageTo() == null ? 0 : c.getPageTo(),
                        c.getPageCount(), stateOf(c)));
            }
        }

        ChunkCompileGuard.ChunkAllowance a = chunkCompileGuard.allowance(userId);
        return new ChaptersResult(a.used(), a.limit(), chapters);
    }

    private String stateOf(KnowledgeFile c) {
        if (c.getCompiledBy() != null) return "COMPILED";
        if (c.getStatus() == KnowledgeFile.Status.READY) return "COMPILING"; // picked, awaiting compile
        return "LOCKED"; // PENDING_CHUNK — not compiled yet
    }
}
