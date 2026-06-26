package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiQualityVerifier;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PART 0 — the WikiQualityVerifier's 0.0–1.0 score is persisted onto the
 * page as a 0–100 quality score during compile. Without this, every
 * INFERRED page kept its default qualityScore=0 and reviewState would
 * always report LOW_CONFIDENCE.
 *
 * <p>Zero real Claude API calls — all AI components are mocked, and a real
 * WikiQualityVerifier (heuristic-only, no LLM) is used.
 */
@ExtendWith(MockitoExtension.class)
class WikiPageQualityScoreTest {

    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock HintTreeGenerator hintTreeGenerator;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock ClaudeApiClient claudeApiClient;
    @Mock ModelRouter modelRouter;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock ModuleContentGenerator moduleContentGenerator;
    @Mock LearningModuleJpaRepository learningModuleRepository;

    private WikiPagePersistenceService service;

    private static final String AVATAR_ID = "avatar-quality-test";
    private static final String USER_ID = "user-quality";

    private Avatar avatar;

    @BeforeEach
    void setUp() {
        WikiQualityVerifier wikiQualityVerifier = new WikiQualityVerifier();
        org.springframework.beans.factory.ObjectProvider<WikiPagePersistenceService> selfProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator,
                flashcardGenerator, claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, wikiQualityVerifier,
                selfProvider,
                org.mockito.Mockito.mock(com.pally.domain.knowledge.WikiConflictService.class));
        org.mockito.Mockito.lenient().when(selfProvider.getObject()).thenReturn(service);

        avatar = Avatar.reconstitute(
                AVATAR_ID, USER_ID, "Bolt", Subject.SCIENCE, CharacterType.ZAP,
                0, Instant.now());

        when(wikiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(wikiRepository.countActiveByAvatarId(AVATAR_ID)).thenReturn(1);
        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(hintTreeGenerator).generateForPage(any(), any());
        doNothing().when(flashcardGenerator).generateAndSaveForPage(any(), any());
    }

    @Test
    void persistDrafts_longCleanPage_endsWithFullQualityScore() {
        // A long (>50 words), arithmetic-clean page → verifier score 1.0 → 100.
        String content = "Plants make their own food using a process called photosynthesis. "
                + "They take in carbon dioxide from the air and water from the soil. "
                + "Using energy from sunlight, the leaves turn these into glucose and oxygen. "
                + "The glucose is sugar the plant uses for energy and growth. "
                + "The oxygen is released into the air for animals and people to breathe. "
                + "This happens in tiny green parts of the leaf called chloroplasts. "
                + "Without sunlight plants cannot make food and would slowly die.";
        WikiCompilerPort.WikiPageDraft draft = new WikiCompilerPort.WikiPageDraft(
                "photosynthesis", "Photosynthesis", content, List.of());

        // The verify loop re-fetches the persisted page by slug — return a real page.
        WikiPage persisted = WikiPage.create(AVATAR_ID, "photosynthesis", "Photosynthesis", content);
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, "photosynthesis"))
                .thenReturn(Optional.empty())   // create path
                .thenReturn(Optional.of(persisted)); // verify-loop re-fetch
        when(wikiRepository.findActiveByAvatarId(AVATAR_ID)).thenReturn(List.of(persisted));

        service.persistDrafts(avatar, List.of(draft), List.of());

        // Capture every page save; the LAST save of this slug carries the verifier score.
        ArgumentCaptor<WikiPage> pageCaptor = ArgumentCaptor.forClass(WikiPage.class);
        verify(wikiRepository, atLeastOnce()).save(pageCaptor.capture());

        WikiPage scored = pageCaptor.getAllValues().stream()
                .filter(p -> "photosynthesis".equals(p.getSlug()))
                .reduce((a, b) -> b)   // last
                .orElseThrow();
        assertThat(scored.getQualityScore())
                .as("clean long page should end with the verifier's full 100 score, not the default 0")
                .isEqualTo(100);
    }

    @Test
    void persistDrafts_shortPage_endsWithReducedQualityScore() {
        // < 50 words → verifier deducts 0.3 → 0.7 → 70.
        String content = "Short note about cells.";
        WikiCompilerPort.WikiPageDraft draft = new WikiCompilerPort.WikiPageDraft(
                "cells", "Cells", content, List.of());

        WikiPage persisted = WikiPage.create(AVATAR_ID, "cells", "Cells", content);
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, "cells"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persisted));
        when(wikiRepository.findActiveByAvatarId(AVATAR_ID)).thenReturn(List.of(persisted));

        service.persistDrafts(avatar, List.of(draft), List.of());

        ArgumentCaptor<WikiPage> pageCaptor = ArgumentCaptor.forClass(WikiPage.class);
        verify(wikiRepository, atLeastOnce()).save(pageCaptor.capture());

        WikiPage scored = pageCaptor.getAllValues().stream()
                .filter(p -> "cells".equals(p.getSlug()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(scored.getQualityScore())
                .as("short page should be penalised below 100 by the verifier")
                .isEqualTo(70);
    }
}
