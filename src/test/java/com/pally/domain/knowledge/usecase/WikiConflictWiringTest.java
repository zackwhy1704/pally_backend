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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part A wiring inside writeSingleDraft: a detected conflict opens a teacher review
 * entry (newest value stays live for students), and a teacher-RESOLVED page is locked
 * — a recompile that would change it opens a NEW entry and does NOT overwrite.
 */
@ExtendWith(MockitoExtension.class)
class WikiConflictWiringTest {

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
    private static final String SLUG = "mitochondria";

    @BeforeEach
    void setUp() {
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, new WikiQualityVerifier(),
                selfProvider, wikiConflictService);
    }

    private WikiCompilerPort.WikiPageDraft draft(String content) {
        return new WikiCompilerPort.WikiPageDraft(SLUG, "Mitochondria", content);
    }

    @Test
    void detectedConflictOnUpdate_opensATeacherEntry_andKeepsNewestLive() {
        WikiPage existing = WikiPage.create(AV, SLUG, "Mitochondria",
                "The mitochondria produces 38 ATP per glucose molecule.");
        when(wikiRepository.findByAvatarIdAndSlug(AV, SLUG)).thenReturn(Optional.of(existing));
        when(wikiConflictService.isResolvedLocked(AV, SLUG)).thenReturn(false);
        when(wikiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.writeSingleDraft(AV, SLUG,
                draft("The mitochondria produces 36 ATP per glucose molecule."), List.of());

        // Teacher review entry opened with the concrete deterministic clash.
        verify(wikiConflictService).open(eq(AV), eq(SLUG), any(), any(),
                contains("38"), eq("DETERMINISTIC"));
        // Newest value still applied (last-write-wins live for students).
        verify(wikiRepository).save(any());
    }

    @Test
    void recompileOfAResolvedPage_opensNewEntry_andDoesNotOverwrite() {
        WikiPage resolved = WikiPage.create(AV, SLUG, "Mitochondria",
                "The mitochondria produces 36 ATP per glucose molecule.");
        when(wikiRepository.findByAvatarIdAndSlug(AV, SLUG)).thenReturn(Optional.of(resolved));
        when(wikiConflictService.isResolvedLocked(AV, SLUG)).thenReturn(true);

        WikiPagePersistenceService.WriteResult r = service.writeSingleDraft(AV, SLUG,
                draft("The mitochondria produces 38 ATP per glucose molecule."), List.of());

        // A new conflict is queued; the resolved page is NOT overwritten.
        verify(wikiConflictService).open(eq(AV), eq(SLUG), any(), any(), any(), any());
        verify(wikiRepository, never()).save(any());
        assertThat(r.created()).isFalse();
    }
}
