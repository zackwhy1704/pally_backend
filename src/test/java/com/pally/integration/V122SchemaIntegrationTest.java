package com.pally.integration;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaEntity;
import com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V122 widened knowledge_files.file_name + chunk_title to TEXT. A PDF bookmark chapter title
 * longer than the old VARCHAR(255) — the exact "Sales Game" failure — must now persist instead
 * of 400-ing the whole segmentation. Runs against real Postgres + the real Flyway migrations.
 */
class V122SchemaIntegrationTest extends IntegrationTestBase {

    @Autowired private KnowledgeFileJpaRepository repo;
    @Autowired private AvatarJpaRepository avatarRepo;

    @Test
    void chunkWithOver255CharTitle_persists() {
        String ownerId = newUserRow();
        AvatarJpaEntity avatar = AvatarJpaEntity.fromDomain(
                Avatar.create(ownerId, "Corpus", Subject.MATHS, CharacterType.MOCHI));
        avatarRepo.save(avatar);

        String longTitle = "T".repeat(300); // > old VARCHAR(255)

        KnowledgeFileJpaEntity e = new KnowledgeFileJpaEntity();
        e.setId(IdGenerator.newId());
        e.setAvatarId(avatar.getId());
        e.setUserId(ownerId);
        e.setFileName(longTitle);      // chunks reuse file_name for the title
        e.setChunkTitle(longTitle);    // and chunk_title — both widened to TEXT
        e.setStorageKey("s3://k");
        e.setPageCount(10);
        e.setUploadType(KnowledgeFile.UploadType.PDF);
        e.setStatus(KnowledgeFile.Status.PENDING_CHUNK);
        e.setCreatedAt(Instant.now());

        repo.saveAndFlush(e); // would throw "value too long for varchar(255)" before V122

        KnowledgeFileJpaEntity loaded = repo.findById(e.getId()).orElseThrow();
        assertThat(loaded.getFileName()).hasSize(300);
        assertThat(loaded.getChunkTitle()).hasSize(300);
    }
}
