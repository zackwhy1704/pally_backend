package com.pally.domain.knowledge.usecase;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.port.WikiCompilerPort.WikiPageDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fan-out cost gate: on a page UPDATE, the per-page generators (hint-tree +
 * flashcards) fire ONLY when the CONTENT actually changed. A metadata-only touch
 * or an identical recompile must fire zero generators — that stops the re-bill
 * bleed AND preserves SM-2 review state (flashcard regen deletes-before-writes).
 */
@ExtendWith(MockitoExtension.class)
class WikiFanOutGateTest {

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

    private WikiPagePersistenceService service() {
        return new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo, moduleContentGenerator,
                learningModuleRepository, wikiQualityVerifier, selfProvider, wikiConflictService);
    }

    private void stubExisting(String content) {
        WikiPage existing = WikiPage.create("av-1", "photosynthesis", "Photosynthesis", content);
        when(wikiRepository.findByAvatarIdAndSlug("av-1", "photosynthesis"))
                .thenReturn(Optional.of(existing));
        lenient().when(wikiConflictService.isResolvedLocked("av-1", "photosynthesis")).thenReturn(false);
        lenient().when(wikiRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void identicalContentRecompile_firesZeroGenerators_preservingSm2() {
        stubExisting("Plants convert light into chemical energy.");
        // Same content (whitespace-normalised) → no real change.
        var draft = new WikiPageDraft("photosynthesis", "Photosynthesis",
                "Plants convert light into   chemical energy.");

        service().writeSingleDraft("av-1", "photosynthesis", draft, List.of(), "en");

        verify(hintTreeGenerator, never()).generateForPage(anyString(), any());
        verify(flashcardGenerator, never()).regenerateForPage(anyString(), any());
    }

    @Test
    void changedContent_firesTheGenerators() {
        stubExisting("Plants convert light into chemical energy.");
        var draft = new WikiPageDraft("photosynthesis", "Photosynthesis",
                "Photosynthesis happens in the chloroplasts and produces glucose and oxygen.");

        service().writeSingleDraft("av-1", "photosynthesis", draft, List.of(), "en");

        verify(hintTreeGenerator, times(1)).generateForPage(anyString(), any());
        verify(flashcardGenerator, times(1)).regenerateForPage(anyString(), any());
    }
}
