package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.HintTreeGenerator;
import com.pally.domain.knowledge.WikiConflictService;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiQualityVerifier;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 1b.5a: the page's content_language is tagged from the avatar at the single write chokepoint
 * (writeSingleDraft — every compile route, including per-draft persistDrafts, flows through it).
 * Language is an attribute of the ARTIFACT (the material), so downstream flashcard/teach/prove/quiz
 * follow it rather than the reader. Captures the saved WikiPage on both the create and recompile
 * branches. 'en' is the default by decision — never a null reaching the tag.
 */
@ExtendWith(MockitoExtension.class)
class WikiPageLanguageTaggingTest {

    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock HintTreeGenerator hintTreeGenerator;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock ClaudeApiClient claudeApiClient;
    @Mock ModelRouter modelRouter;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock ModuleContentGenerator moduleContentGenerator;
    @Mock LearningModuleJpaRepository learningModuleRepository;
    @Mock ObjectProvider<WikiPagePersistenceService> selfProvider;
    @Mock WikiConflictService wikiConflictService;

    private WikiPagePersistenceService service;

    private static final String AV = "av-1";
    private static final String SLUG = "photosynthesis";

    @BeforeEach
    void setUp() {
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, new WikiQualityVerifier(),
                selfProvider, wikiConflictService);
    }

    private WikiCompilerPort.WikiPageDraft draft(String content) {
        return new WikiCompilerPort.WikiPageDraft(SLUG, "Photosynthesis", content);
    }

    private WikiPage captureSaved(String contentLanguage) {
        ArgumentCaptor<WikiPage> cap = ArgumentCaptor.forClass(WikiPage.class);
        when(wikiRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));
        service.writeSingleDraft(AV, SLUG, draft("content"), List.of(), contentLanguage);
        return cap.getValue();
    }

    @Test
    void newPage_taggedZh_whenAvatarIsZh() {
        when(wikiRepository.findByAvatarIdAndSlug(AV, SLUG)).thenReturn(Optional.empty());
        assertThat(captureSaved("zh").getContentLanguage()).isEqualTo("zh");
    }

    @Test
    void newPage_taggedEn_whenAvatarIsEn() {
        when(wikiRepository.findByAvatarIdAndSlug(AV, SLUG)).thenReturn(Optional.empty());
        assertThat(captureSaved("en").getContentLanguage()).isEqualTo("en");
    }

    @Test
    void recompile_retagsToCurrentLanguage() {
        // An existing en page recompiled with new content in a zh class → the artifact is retagged zh.
        WikiPage existing = WikiPage.create(AV, SLUG, "Photosynthesis", "old content");
        assertThat(existing.getContentLanguage()).isEqualTo("en"); // starts en
        when(wikiRepository.findByAvatarIdAndSlug(AV, SLUG)).thenReturn(Optional.of(existing));
        lenient().when(wikiConflictService.isResolvedLocked(AV, SLUG)).thenReturn(false);

        ArgumentCaptor<WikiPage> cap = ArgumentCaptor.forClass(WikiPage.class);
        when(wikiRepository.save(cap.capture())).thenAnswer(inv -> inv.getArgument(0));
        service.writeSingleDraft(AV, SLUG, draft("brand new content"), List.of(), "zh");

        assertThat(cap.getValue().getContentLanguage()).isEqualTo("zh");
    }
}
