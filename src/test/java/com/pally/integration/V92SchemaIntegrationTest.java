package com.pally.integration;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.usecase.DurableCompileStatusStore;
import com.pally.domain.knowledge.usecase.FailedPage;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V92 unblocks two DataIntegrity failures the centre content-quality eval surfaced
 * against the real schema:
 *   1. an assignment with NO deadline must persist (due_date is now optional), and
 *   2. an LLM-produced wiki slug/title longer than the old VARCHAR bounds must
 *      persist instead of failing the whole compile with "value too long".
 * These run against real Postgres + the real Flyway migrations.
 */
class V92SchemaIntegrationTest extends IntegrationTestBase {

    @Autowired private AssignmentJpaRepository assignmentRepo;
    @Autowired private WikiPageJpaRepository wikiRepo;
    @Autowired private DurableCompileStatusStore durableCompileStatusStore;
    @Autowired private LearningModuleJpaRepository moduleRepo;
    @Autowired private AvatarJpaRepository avatarRepo;
    @Autowired private OrganizationJpaRepository orgRepo;
    @Autowired private OrgClassJpaRepository classRepo;
    @Autowired private UserJpaRepository userRepo;

    @Test
    void assignment_withNullDueDate_persists() {
        UserJpaEntity owner = new UserJpaEntity();
        owner.setId(IdGenerator.newId());
        owner.setEmail("owner-" + IdGenerator.newId() + "@eval.test");
        owner.setDisplayName("Owner");
        owner.setCreatedAt(Instant.now());
        userRepo.save(owner);

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(IdGenerator.newId());
        org.setName("Eval Centre");
        org.setOwnerUserId(owner.getId());
        org.setSeatLimit(30);
        org.setCreatedAt(Instant.now());
        orgRepo.save(org);

        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId(IdGenerator.newId());
        cls.setOrganizationId(org.getId());
        cls.setName("P4 Math");
        cls.setJoinCode("JOIN-" + org.getId().substring(0, 6));
        cls.setCreatedAt(Instant.now());
        classRepo.save(cls);

        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId(IdGenerator.newId());
        a.setClassId(cls.getId());
        a.setTitle("No-deadline revision");
        a.setType("REVISION");
        a.setModuleIds("mod-1,mod-2");
        a.setDueDate(null);                 // the case that used to violate NOT NULL
        a.setCreatedBy("teacher-1");
        a.setCreatedAt(Instant.now());

        assignmentRepo.saveAndFlush(a);

        AssignmentJpaEntity loaded = assignmentRepo.findById(a.getId()).orElseThrow();
        assertThat(loaded.getDueDate()).isNull();
        assertThat(loaded.getModuleIds()).isEqualTo("mod-1,mod-2");
    }

    @Test
    void wikiPage_withLongSlugAndTitle_persists() {
        AvatarJpaEntity avatar = AvatarJpaEntity.fromDomain(
                Avatar.create("teacher-1", "Corpus", Subject.MATHS, CharacterType.MOCHI));
        avatarRepo.save(avatar);

        String longSlug = "a".repeat(150);       // > old VARCHAR(100)
        String longTitle = "T".repeat(400);      // > old VARCHAR(255)

        WikiPageJpaEntity p = new WikiPageJpaEntity();
        p.setId(IdGenerator.newId());
        p.setAvatarId(avatar.getId());
        p.setSlug(longSlug);
        p.setTitle(longTitle);
        p.setContent("body");
        p.setCertainty(WikiPage.Certainty.INFERRED);
        p.setUpdatedAt(Instant.now());
        p.setStatus(WikiPage.Status.ACTIVE);

        wikiRepo.saveAndFlush(p);

        WikiPageJpaEntity loaded = wikiRepo.findById(p.getId()).orElseThrow();
        assertThat(loaded.getSlug()).hasSize(150);
        assertThat(loaded.getTitle()).hasSize(400);
    }

    @Test
    void wikiPage_withLongConflictNote_includingEmoji_persists() {
        // conflict_note is now TEXT (V93). A note longer than the old VARCHAR(500),
        // containing a surrogate-pair emoji near the old boundary, must round-trip —
        // the old raw substring(0,500) could have split the pair and failed the write.
        AvatarJpaEntity avatar = AvatarJpaEntity.fromDomain(
                Avatar.create("teacher-1", "Corpus", Subject.SCIENCE, CharacterType.MOCHI));
        avatarRepo.save(avatar);

        String longNote = "n".repeat(499) + "😀" + "n".repeat(200);  // emoji straddles old 500 cap

        WikiPageJpaEntity p = new WikiPageJpaEntity();
        p.setId(IdGenerator.newId());
        p.setAvatarId(avatar.getId());
        p.setSlug("photosynthesis");
        p.setTitle("Photosynthesis");
        p.setContent("body");
        p.setCertainty(WikiPage.Certainty.INFERRED);
        p.setUpdatedAt(Instant.now());
        p.setStatus(WikiPage.Status.ACTIVE);
        p.setConflictNote(longNote);

        wikiRepo.saveAndFlush(p);

        WikiPageJpaEntity loaded = wikiRepo.findById(p.getId()).orElseThrow();
        assertThat(loaded.getConflictNote()).isEqualTo(longNote);
    }

    @Test
    void learningModule_withLongTitle_persists() {
        // learning_module.title is now TEXT (V94) — it's copied from the (now TEXT)
        // wiki page title, so a long generated title must not fail module generation.
        AvatarJpaEntity avatar = AvatarJpaEntity.fromDomain(
                Avatar.create("teacher-1", "Corpus", Subject.MATHS, CharacterType.MOCHI));
        avatarRepo.save(avatar);

        String longTitle = "T".repeat(800);   // > old VARCHAR(500)

        LearningModuleJpaEntity m = new LearningModuleJpaEntity();
        m.setId(IdGenerator.newId());
        m.setAvatarId(avatar.getId());
        m.setWikiPageSlug("speed-distance-time");
        m.setTitle(longTitle);
        m.setStage("LEARN");
        m.setTier("FREE");
        m.setCreatedAt(Instant.now());

        moduleRepo.saveAndFlush(m);

        LearningModuleJpaEntity loaded = moduleRepo.findById(m.getId()).orElseThrow();
        assertThat(loaded.getTitle()).hasSize(800);
    }
    @Test
    void compileStatus_durableRoundTrip_persistsFailedPagesAcrossInstances() {
        // C2: the durable row survives instance restarts/replicas, so the partial
        // compile's failedPages are still readable on a different instance.
        durableCompileStatusStore.record("av-durable", "DONE", 6, 8,
                java.util.List.of(new FailedPage("osmosis", "conflict_note")));

        DurableCompileStatusStore.DurableStatus loaded =
                durableCompileStatusStore.find("av-durable").orElseThrow();
        assertThat(loaded.state()).isEqualTo("DONE");
        assertThat(loaded.pagesCompiled()).isEqualTo(6);
        assertThat(loaded.pagesTotal()).isEqualTo(8);
        assertThat(loaded.pagesFailed()).isEqualTo(1);
        assertThat(loaded.failedPages()).extracting(FailedPage::slug).containsExactly("osmosis");
    }
}
