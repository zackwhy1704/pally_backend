package com.pally.integration;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.CentreAnalyticsService;
import com.pally.domain.centre.ClassCrudService;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke for the centre content pipeline against a real Postgres
 * (Testcontainers): a centre creates a class → its corpus avatar is bound to the
 * class → a compiled module tagged to that corpus is loaded by the class-modules
 * view. This pins Bug #3 (orphaned modules) at the persistence layer — the part a
 * mock can't catch, since it's a classId round-trip through the DB.
 *
 * <p>The teacher review + regenerate surfaces are exercised by
 * ContentReviewServiceTest / CentreRegenerateServiceTest (the regenerate path
 * makes a real Claude call, so it stays at the unit layer with the AI port mocked).
 */
class CentreContentPipelineSmokeTest extends IntegrationTestBase {

    @Autowired OrganizationJpaRepository orgRepo;
    @Autowired ClassCrudService classCrudService;
    @Autowired CentreAnalyticsService centreAnalyticsService;
    @Autowired AvatarRepository avatarRepository;
    @Autowired LearningModuleJpaRepository moduleRepo;

    @Test
    void centreCreatesClass_corpusIsBound_andCompiledModulesLoad() {
        // 1. A centre owner + their organisation.
        AuthResult owner = registerConsentedUser(
                "centre-owner-" + System.nanoTime() + "@test.com", "password123");
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(IdGenerator.newId());
        org.setName("Smoke Test Centre");
        org.setOwnerUserId(owner.userId()); // classLimit defaults to 0 (unlimited)
        orgRepo.save(org);

        // 2. Create a class (the fixed path).
        Map<String, Object> created = classCrudService.createClass(
                owner.userId(), org.getId(),
                Map.of("name", "P5 Science", "subject", "SCIENCE",
                        "level", "P5", "characterType", "MOCHI"));
        String classId = (String) created.get("id");
        String corpusAvatarId = (String) created.get("corpusAvatarId");
        assertThat(classId).isNotBlank();
        assertThat(corpusAvatarId).isNotBlank();

        // 3. Bug #3 fix — the corpus avatar round-trips its classId through the DB.
        Avatar corpus = avatarRepository.findById(corpusAvatarId).orElseThrow();
        assertThat(corpus.getClassId())
                .as("corpus avatar must be bound to its class (else modules orphan)")
                .isEqualTo(classId);

        // Before any module exists, the class-modules view is cleanly empty.
        assertThat(centreAnalyticsService.classModules(classId)).isEmpty();

        // 4. Simulate a compiled module tagged to the corpus (real compile needs AI;
        //    the classId tag is exactly what wiki compile sets from avatar.getClassId()).
        LearningModuleJpaEntity mod = new LearningModuleJpaEntity();
        mod.setId(IdGenerator.newId());
        mod.setAvatarId(corpusAvatarId);
        mod.setClassId(classId);
        mod.setWikiPageSlug("photosynthesis");
        mod.setTitle("Photosynthesis");
        mod.setStage("COMPLETE");
        mod.setTier("FREE");
        mod.setMasteryPct(BigDecimal.valueOf(0));
        mod.setCreatedAt(Instant.now());
        moduleRepo.save(mod);

        // 5. "Right modules load" — the class-modules view returns the tagged module.
        List<Map<String, Object>> modules = centreAnalyticsService.classModules(classId);
        assertThat(modules)
                .as("a module tagged to the class corpus must surface in classModules")
                .anySatisfy(m -> {
                    assertThat(m.get("title")).isEqualTo("Photosynthesis");
                    assertThat(m.get("wikiSlug")).isEqualTo("photosynthesis");
                    assertThat(m.get("stage")).isEqualTo("COMPLETE");
                });

        // Tidy up the non-@test.com rows this test created (best-effort).
        moduleRepo.deleteById(mod.getId());
        orgRepo.deleteById(org.getId());
    }
}
