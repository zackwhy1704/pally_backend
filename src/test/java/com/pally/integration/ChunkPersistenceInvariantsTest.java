package com.pally.integration;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence invariants for chapter chunks, against real Postgres + real Flyway
 * (V113). These pin the two guarantees the design leans on:
 *   1. A SEGMENTED parent + PENDING_CHUNK children are INVISIBLE to the recompile
 *      reconciler (they are not READY) — the reconciler was a zombie factory once.
 *   2. Chunks never inflate the upload doc-cap; a SEGMENTED parent counts as one
 *      upload; and the chunk-compile allowance counts only SUCCESSFULLY compiled
 *      children within the rolling window.
 */
class ChunkPersistenceInvariantsTest extends IntegrationTestBase {

    @Autowired private AvatarJpaRepository avatarRepo;
    @Autowired private KnowledgeRepository knowledgeRepo;
    @Autowired private WikiPageJpaRepository wikiRepo;

    private String newAvatar(String userId) {
        Avatar a = Avatar.create(userId, "Mochi", Subject.MATHS, CharacterType.MOCHI);
        avatarRepo.save(AvatarJpaEntity.fromDomain(a));
        return a.getId();
    }

    private KnowledgeFile segmentedParent(String avatarId, String userId) {
        KnowledgeFile p = KnowledgeFile.create(avatarId, userId, "textbook.pdf", "k/t.pdf",
                KnowledgeFile.UploadType.PDF);
        p.setExtractedText("full textbook text");
        p.markReady(300);
        p.markSegmented();
        return knowledgeRepo.save(p);
    }

    @Test
    void segmentedParentAndPendingChunks_areInvisibleToTheReconciler() {
        String userId = newUserRow();
        String avatarId = newAvatar(userId);
        KnowledgeFile parent = segmentedParent(avatarId, userId);
        // two unpicked chunks
        knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "chapter one text"));
        knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 2", 26, 50, 25, "chapter two text"));

        // NONE of these are READY, so the "0 active pages" zombie branch must not fire.
        assertThat(wikiRepo.findAvatarIdsNeedingRecompile()).doesNotContain(avatarId);
    }

    @Test
    void pickedChunk_isReady_andThenVisibleAsRealWork() {
        String userId = newUserRow();
        String avatarId = newAvatar(userId);
        KnowledgeFile parent = segmentedParent(avatarId, userId);
        KnowledgeFile chunk = knowledgeRepo.save(
                KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "chapter one text"));

        // Before pick: not flagged.
        assertThat(wikiRepo.findAvatarIdsNeedingRecompile()).doesNotContain(avatarId);

        // Pick it → READY + compiled_by null = genuine uncompiled work.
        chunk.markPicked();
        knowledgeRepo.save(chunk);
        assertThat(wikiRepo.findAvatarIdsNeedingRecompile()).contains(avatarId);
    }

    @Test
    void findByParentAndHasChunks_resolveChildren() {
        String userId = newUserRow();
        String avatarId = newAvatar(userId);
        KnowledgeFile parent = segmentedParent(avatarId, userId);
        knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "a"));
        knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 2", 26, 50, 25, "b"));

        assertThat(knowledgeRepo.hasChunks(parent.getId())).isTrue();
        assertThat(knowledgeRepo.findByParentFileId(parent.getId())).hasSize(2);
        assertThat(knowledgeRepo.findByParentFileId("no-such-parent")).isEmpty();
    }

    @Test
    void docCap_countsSegmentedParentOnce_neverTheChildChunks() {
        String userId = newUserRow();
        String avatarId = newAvatar(userId);
        KnowledgeFile parent = segmentedParent(avatarId, userId);
        // three chunks, one of them picked (READY)
        knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "a"));
        knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 2", 26, 50, 25, "b"));
        KnowledgeFile picked = knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 3", 51, 75, 25, "c"));
        picked.markPicked(); // READY
        knowledgeRepo.save(picked);

        // The doc cap must see exactly ONE upload (the parent), not the READY child.
        int uploads = knowledgeRepo.countAcceptedUploadsSince(userId, Instant.now().minusSeconds(3600));
        assertThat(uploads).isEqualTo(1);
    }

    @Test
    void chunkCompileCount_countsOnlySuccessfullyCompiledChildrenInWindow() {
        String userId = newUserRow();
        String avatarId = newAvatar(userId);
        KnowledgeFile parent = segmentedParent(avatarId, userId);

        KnowledgeFile compiled = knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "a"));
        compiled.markPicked();
        compiled.markCompiled("gemini-2.5-flash"); // stamps compiled_at = now
        knowledgeRepo.save(compiled);

        // A picked-but-failed chunk (READY, no compiled_at) must NOT count.
        KnowledgeFile failed = knowledgeRepo.save(KnowledgeFile.createChunk(parent, "Ch 2", 26, 50, 25, "b"));
        failed.markPicked();
        knowledgeRepo.save(failed);

        int count = knowledgeRepo.countChunkCompilesSince(userId, Instant.now().minusSeconds(3600));
        assertThat(count).isEqualTo(1); // only the successfully-compiled chunk burns allowance
    }
}
