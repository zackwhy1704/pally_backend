package com.pally.integration;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaEntity;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository;
import com.pally.infrastructure.ai.GeminiThinkingBudgetConfig;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup reconciler ({@code WikiRecompileScheduler.reconcileOnStartup}) enqueues
 * a recompile for every avatarId returned by {@code findAvatarIdsNeedingRecompile()}.
 *
 * <p>ZOMBIE BUG: the "0 active wiki pages" branch used to fire for an avatar whose
 * READY files were ALL already compiled (compiled_by set) but produced 0 active
 * pages — yet executeBatched skips already-compiled files, so the recompile was a
 * guaranteed no-op that re-flagged the same avatar on the NEXT restart, forever.
 * The guard: a zero-page avatar is only flagged when there is genuinely uncompiled
 * work (a READY file with compiled_by IS NULL). These run against real Postgres +
 * the real Flyway migrations, exercising the native SQL directly.
 */
class ZombieRecompileReconcilerTest extends IntegrationTestBase {

    @Autowired private AvatarJpaRepository avatarRepo;
    @Autowired private KnowledgeFileJpaRepository fileRepo;
    @Autowired private WikiPageJpaRepository wikiRepo;
    @Autowired private GeminiThinkingBudgetConfig thinkingConfig;

    @Test
    void thinkingBudgetConfig_bindsFromYaml_extractionOff_reasoningAbsent() {
        // Proves the real application.yml map binds (hyphenated keys → purpose labels):
        // extraction purposes disable thinking, the reasoning evals are unlisted so the
        // provider default (thinking ON) stands.
        assertThat(thinkingConfig.budgetFor("module-learn-gen")).isZero();
        assertThat(thinkingConfig.budgetFor("topic-router")).isZero();
        assertThat(thinkingConfig.budgetFor("wiki-compile")).isZero();
        assertThat(thinkingConfig.budgetFor("teach-eval")).isNull();
        assertThat(thinkingConfig.budgetFor("module-prove-eval")).isNull();
    }

    private String newAvatar() {
        Avatar a = Avatar.create("user-" + System.nanoTime(), "Mochi", Subject.MATHS, CharacterType.MOCHI);
        avatarRepo.save(AvatarJpaEntity.fromDomain(a));
        return a.getId();
    }

    private void readyFile(String avatarId, String compiledBy) {
        KnowledgeFileJpaEntity f = new KnowledgeFileJpaEntity();
        f.setId("f-" + System.nanoTime());
        f.setAvatarId(avatarId);
        f.setUserId("user-x");
        f.setFileName("notes.pdf");
        f.setStorageKey("k/notes.pdf");
        f.setPageCount(3);
        f.setUploadType(KnowledgeFile.UploadType.PDF);
        f.setStatus(KnowledgeFile.Status.READY);
        f.setCreatedAt(Instant.now().minusSeconds(3600));
        f.setCompiledBy(compiledBy); // null = not yet compiled
        fileRepo.save(f);
    }

    private void activePage(String avatarId, String slug) {
        WikiPageJpaEntity p = new WikiPageJpaEntity();
        p.setId("p-" + System.nanoTime());
        p.setAvatarId(avatarId);
        p.setSlug(slug);
        p.setTitle("Title");
        p.setContent("Body");
        p.setCertainty(WikiPage.Certainty.INFERRED);
        p.setUpdatedAt(Instant.now()); // newer than the file's created_at above
        p.setStatus(WikiPage.Status.ACTIVE);
        wikiRepo.save(p);
    }

    @Test
    void allFilesCompiled_zeroActivePages_isNotFlagged_theZombie() {
        String zombie = newAvatar();
        readyFile(zombie, "gemini-2.5-flash"); // already compiled
        // 0 active wiki pages (compile produced none / all archived) — no uncompiled work.

        assertThat(wikiRepo.findAvatarIdsNeedingRecompile()).doesNotContain(zombie);
    }

    @Test
    void uncompiledFile_zeroActivePages_isFlagged_realWorkRemains() {
        String pending = newAvatar();
        readyFile(pending, null); // NOT yet compiled → genuine work

        assertThat(wikiRepo.findAvatarIdsNeedingRecompile()).contains(pending);
    }

    @Test
    void compiledFileNewerThanPages_stillFlagged_incrementalCasePreserved() {
        // A compiled file whose created_at is newer than the newest active page — the
        // legitimate incremental branch — must still be flagged (guard didn't break it).
        String incremental = newAvatar();
        activePage(incremental, "old-topic");
        KnowledgeFileJpaEntity f = new KnowledgeFileJpaEntity();
        f.setId("f-" + System.nanoTime());
        f.setAvatarId(incremental);
        f.setUserId("user-x");
        f.setFileName("new.pdf");
        f.setStorageKey("k/new.pdf");
        f.setPageCount(1);
        f.setUploadType(KnowledgeFile.UploadType.PDF);
        f.setStatus(KnowledgeFile.Status.READY);
        f.setCreatedAt(Instant.now().plusSeconds(3600)); // newer than the page
        f.setCompiledBy("gemini-2.5-flash");
        fileRepo.save(f);

        assertThat(wikiRepo.findAvatarIdsNeedingRecompile()).contains(incremental);
    }
}
