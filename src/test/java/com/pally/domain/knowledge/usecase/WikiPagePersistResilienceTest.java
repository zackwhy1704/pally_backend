package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.HintTreeGenerator;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Page-level persist resilience: each page writes in its OWN transaction, so one
 * page's DataIntegrity (e.g. a value reaching a too-narrow column) must NOT sink the
 * rest of its batch. The orchestrator records the failure with its slug + cause and
 * keeps going — partial content beats a blanket 400.
 */
@ExtendWith(MockitoExtension.class)
class WikiPagePersistResilienceTest {

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
    @Mock WikiPagePersistenceService selfMock;

    private WikiPagePersistenceService service;

    private final Avatar avatar = Avatar.reconstitute(
            "av-1", "u-1", "Mochi", Subject.SCIENCE, CharacterType.MOCHI, 0, Instant.now());

    @BeforeEach
    void setUp() {
        service = new WikiPagePersistenceService(
                wikiRepository, avatarRepository, hintTreeGenerator, flashcardGenerator,
                claudeApiClient, modelRouter, wikiPageSourceRepo,
                moduleContentGenerator, learningModuleRepository, new WikiQualityVerifier(),
                selfProvider,
                org.mockito.Mockito.mock(com.pally.domain.knowledge.WikiConflictService.class));
        // lenient: the pure-static isUniqueViolation test doesn't drive the orchestrator.
        org.mockito.Mockito.lenient().when(selfProvider.getObject()).thenReturn(selfMock);
    }

    @Test
    void oneBadPage_doesNotSinkTheBatch_andIsReportedWithSlugAndCause() {
        var good = new WikiCompilerPort.WikiPageDraft("good", "Good Page", "content A");
        var bad = new WikiCompilerPort.WikiPageDraft("bad", "Bad Page", "content B");

        when(selfMock.writeSingleDraft(eq("av-1"), eq("good"), any(), anyList()))
                .thenReturn(new WikiPagePersistenceService.WriteResult(true, "Good Page"));
        when(selfMock.writeSingleDraft(eq("av-1"), eq("bad"), any(), anyList()))
                .thenThrow(new DataIntegrityViolationException(
                        "value too long for type character varying — conflict_note"));

        WikiPagePersistenceService.PersistOutcome outcome =
                service.persistDrafts(avatar, List.of(good, bad), List.of());

        // The good page survived; the bad one is reported, not fatal.
        assertThat(outcome.created()).isEqualTo(1);
        assertThat(outcome.pageTitles()).containsExactly("Good Page");
        assertThat(outcome.producedSlugs()).containsExactly("good", "bad");
        assertThat(outcome.failedPages()).hasSize(1);
        assertThat(outcome.failedPages().get(0).slug()).isEqualTo("bad");
        assertThat(outcome.failedPages().get(0).reason()).contains("conflict_note");
    }

    @Test
    void uniqueViolationOnAPage_isTreatedAsSuccess_notAFailedPage() {
        // A3: a duplicate-key on (avatar_id, slug) means the page already exists
        // (a residual race), NOT a failure — the teacher must not see "1 page failed".
        var good = new WikiCompilerPort.WikiPageDraft("good", "Good Page", "content A");
        var dup = new WikiCompilerPort.WikiPageDraft("dup", "Dup Page", "content B");

        when(selfMock.writeSingleDraft(eq("av-1"), eq("good"), any(), anyList()))
                .thenReturn(new WikiPagePersistenceService.WriteResult(true, "Good Page"));
        when(selfMock.writeSingleDraft(eq("av-1"), eq("dup"), any(), anyList()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                        + "\"wiki_pages_avatar_id_slug_key\""));

        WikiPagePersistenceService.PersistOutcome outcome =
                service.persistDrafts(avatar, List.of(good, dup), List.of());

        assertThat(outcome.failedPages()).isEmpty();        // dup is NOT surfaced as a failure
        assertThat(outcome.created()).isEqualTo(1);          // only the genuinely-new page counted
    }

    @Test
    void isUniqueViolation_detectsDuplicateKey_butNotOtherDataIntegrity() {
        assertThat(WikiPagePersistenceService.isUniqueViolation(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"x\""))).isTrue();
        assertThat(WikiPagePersistenceService.isUniqueViolation(
                new RuntimeException(new java.sql.SQLException("dup", "23505")))).isTrue();
        // A genuine over-long value (not a uniqueness collision) is still a real failure.
        assertThat(WikiPagePersistenceService.isUniqueViolation(
                new DataIntegrityViolationException(
                        "value too long for type character varying(160)"))).isFalse();
    }
}
