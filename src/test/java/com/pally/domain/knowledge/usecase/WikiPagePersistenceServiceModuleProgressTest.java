package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.module.ModuleGenerationProgressStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proves queueModuleGeneration (private — invoked via reflection, the narrowest seam
 * that exercises the REAL method rather than a re-implementation of it) actually wires
 * ModuleGenerationProgressStore correctly: start() before the loop, increment() per
 * completed page — the exact signal AvatarMapper surfaces to the client's compile poll.
 * Before this wiring, a polling client had zero visibility into this phase; a bug here
 * (wrong avatarId, missing increment, start() never called) would silently restore
 * that blind spot without any compile error.
 */
@ExtendWith(MockitoExtension.class)
class WikiPagePersistenceServiceModuleProgressTest {

    @Mock com.pally.domain.knowledge.WikiRepository wikiRepository;
    @Mock com.pally.domain.avatar.AvatarRepository avatarRepository;
    @Mock com.pally.domain.chat.HintTreeGenerator hintTreeGenerator;
    @Mock com.pally.infrastructure.ai.ClaudeFlashcardGenerator flashcardGenerator;
    @Mock com.pally.infrastructure.ai.ClaudeApiClient claudeApiClient;
    @Mock com.pally.infrastructure.ai.ModelRouter modelRouter;
    @Mock com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock com.pally.domain.module.ModuleContentGenerator moduleContentGenerator;
    @Mock com.pally.infrastructure.persistence.module.LearningModuleJpaRepository learningModuleRepository;
    @Mock com.pally.domain.knowledge.WikiQualityVerifier wikiQualityVerifier;
    @Mock ObjectProvider<WikiPagePersistenceService> selfProvider;
    @Mock com.pally.domain.knowledge.WikiConflictService wikiConflictService;

    private ModuleGenerationProgressStore progressStore;
    private WikiPagePersistenceService service;
    private Method queueModuleGeneration;

    @BeforeEach
    void setUp() throws Exception {
        progressStore = new ModuleGenerationProgressStore();
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo, moduleContentGenerator,
                progressStore, learningModuleRepository, wikiQualityVerifier, selfProvider,
                wikiConflictService);
        queueModuleGeneration = WikiPagePersistenceService.class
                .getDeclaredMethod("queueModuleGeneration", Avatar.class, List.class);
        queueModuleGeneration.setAccessible(true);
    }

    private Avatar avatar() {
        return Avatar.create("user-1", "Test Mochi", Subject.SCIENCE, CharacterType.MOCHI);
    }

    @Test
    void start_recordsTotal_beforeAnyPageGenerates() throws Exception {
        Avatar avatar = avatar();
        // Deliberately no stubs for findByAvatarIdAndWikiPageSlug/findByAvatarIdAndSlug —
        // Mockito defaults to Optional.empty(), so every slug takes the "page not found,
        // skip generation" branch. start() must still have recorded the real total.
        queueModuleGeneration.invoke(service, avatar, List.of("slug-a", "slug-b", "slug-c"));

        var progress = progressStore.find(avatar.getId());
        assertThat(progress).isNotNull();
        assertThat(progress.total()).isEqualTo(3);
    }

    @Test
    void increment_firesOncePerSuccessfullyGeneratedPage() throws Exception {
        Avatar avatar = avatar();
        lenient().when(learningModuleRepository.findByAvatarIdAndWikiPageSlug(anyString(), anyString()))
                .thenReturn(Optional.empty());
        WikiPage pageA = WikiPage.create(avatar.getId(), "slug-a", "A", "content a");
        WikiPage pageB = WikiPage.create(avatar.getId(), "slug-b", "B", "content b");
        when(wikiRepository.findByAvatarIdAndSlug(avatar.getId(), "slug-a")).thenReturn(Optional.of(pageA));
        when(wikiRepository.findByAvatarIdAndSlug(avatar.getId(), "slug-b")).thenReturn(Optional.of(pageB));

        queueModuleGeneration.invoke(service, avatar, List.of("slug-a", "slug-b"));

        var progress = progressStore.find(avatar.getId());
        assertThat(progress.completed()).isEqualTo(2);
        assertThat(progress.total()).isEqualTo(2);
    }

    @Test
    void increment_alsoFiresForIdempotentAlreadyExistsSkip() throws Exception {
        // A page whose module already exists is a legitimate "done" for this pass —
        // the client's progress display must not stall on the one page that hits the
        // idempotent skip.
        Avatar avatar = avatar();
        when(learningModuleRepository.findByAvatarIdAndWikiPageSlug(avatar.getId(), "slug-a"))
                .thenReturn(Optional.of(new com.pally.infrastructure.persistence.module.LearningModuleJpaEntity()));

        queueModuleGeneration.invoke(service, avatar, List.of("slug-a"));

        var progress = progressStore.find(avatar.getId());
        assertThat(progress.completed()).isEqualTo(1);
    }

    @Test
    void genuineGenerationFailure_doesNotIncrement() throws Exception {
        // A real failure must NOT be counted as complete — that page's module isn't done.
        Avatar avatar = avatar();
        lenient().when(learningModuleRepository.findByAvatarIdAndWikiPageSlug(anyString(), anyString()))
                .thenReturn(Optional.empty());
        WikiPage page = WikiPage.create(avatar.getId(), "slug-a", "A", "content a");
        when(wikiRepository.findByAvatarIdAndSlug(avatar.getId(), "slug-a")).thenReturn(Optional.of(page));
        when(moduleContentGenerator.generate(any(), any()))
                .thenThrow(new RuntimeException("simulated generation failure"));

        queueModuleGeneration.invoke(service, avatar, List.of("slug-a"));

        var progress = progressStore.find(avatar.getId());
        assertThat(progress.completed()).isZero();
    }
}
