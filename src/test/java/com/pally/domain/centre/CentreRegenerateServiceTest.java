package com.pally.domain.centre;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiPage.Certainty;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentreRegenerateServiceTest {

    @Mock CentreAccessService centreAccessService;
    @Mock OrgClassRepository orgClassRepository;
    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock LearningModuleRepository moduleRepository;
    @Mock ModuleContentGenerator contentGenerator;
    @Mock RegenRateLimiter rateLimiter;

    @InjectMocks CentreRegenerateService service;

    private static final String USER_ID = "user-1";
    private static final String ORG_ID = "org-1";
    private static final String CLASS_ID = "class-1";
    private static final String PAGE_SLUG = "fractions";
    private static final String CORPUS_AVATAR_ID = "corpus-av-1";
    private static final String MODULE_ID = "module-1";

    private Avatar stubAvatar() {
        return Avatar.reconstitute(
                CORPUS_AVATAR_ID, "teacher-1", "P4 Maths",
                Subject.MATHS, CharacterType.MOCHI, 5, Instant.now());
    }

    private WikiPage stubPage() {
        return WikiPage.reconstitute(
                "page-1", CORPUS_AVATAR_ID, PAGE_SLUG, "Fractions", "Content...",
                Certainty.INFERRED, Instant.now());
    }

    private LearningModule stubModule() {
        LearningModule m = new LearningModule();
        m.setId(MODULE_ID);
        m.setAvatarId(CORPUS_AVATAR_ID);
        m.setWikiPageSlug(PAGE_SLUG);
        m.setTitle("Fractions");
        return m;
    }

    private void wireHappyPath() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of(ORG_ID));
        when(orgClassRepository.findCorpusAvatarIdByClassId(CLASS_ID)).thenReturn(Optional.of(CORPUS_AVATAR_ID));
        when(wikiRepository.findByAvatarIdAndSlug(CORPUS_AVATAR_ID, PAGE_SLUG)).thenReturn(Optional.of(stubPage()));
        when(avatarRepository.findById(CORPUS_AVATAR_ID)).thenReturn(Optional.of(stubAvatar()));
        when(moduleRepository.findByAvatarIdAndWikiPageSlug(CORPUS_AVATAR_ID, PAGE_SLUG)).thenReturn(Optional.of(stubModule()));
    }

    @Test
    void regenerate_happyPath_callsGeneratorAndReturnsResult() {
        wireHappyPath();

        Map<String, Object> result = service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, "focus on word problems");

        assertThat(result).containsEntry("pageSlug", PAGE_SLUG)
                          .containsEntry("moduleId", MODULE_ID)
                          .containsEntry("regenerated", true);
        verify(centreAccessService).ensureStaff(USER_ID, ORG_ID);
        verify(rateLimiter).checkAndRecord(CLASS_ID, PAGE_SLUG);
        verify(contentGenerator).regenerateAsDraft(any(Avatar.class), any(WikiPage.class), any(LearningModule.class), eq("focus on word problems"));
    }

    @Test
    void regenerate_nullGuidance_passesNullToGenerator() {
        wireHappyPath();

        service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null);

        verify(contentGenerator).regenerateAsDraft(any(Avatar.class), any(WikiPage.class), any(LearningModule.class), eq(null));
    }

    @Test
    void regenerate_classNotFound_throws404() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");

        verify(contentGenerator, never()).regenerateAsDraft(any(), any(), any(), any());
    }

    @Test
    void regenerate_classBelongsToDifferentOrg_throws403() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of("other-org"));

        assertThatThrownBy(() -> service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("organisation");

        verify(contentGenerator, never()).regenerateAsDraft(any(), any(), any(), any());
    }

    @Test
    void regenerate_rateLimited_throwsBusinessException() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of(ORG_ID));
        doThrow(new BusinessException("Regeneration limit reached", 429))
                .when(rateLimiter).checkAndRecord(CLASS_ID, PAGE_SLUG);

        assertThatThrownBy(() -> service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Regeneration limit reached");

        verify(contentGenerator, never()).regenerateAsDraft(any(), any(), any(), any());
    }

    @Test
    void regenerate_noCorpusAvatar_throws409() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of(ORG_ID));
        when(orgClassRepository.findCorpusAvatarIdByClassId(CLASS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("upload notes");

        verify(contentGenerator, never()).regenerateAsDraft(any(), any(), any(), any());
    }

    @Test
    void regenerate_pageNotFound_throws404() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of(ORG_ID));
        when(orgClassRepository.findCorpusAvatarIdByClassId(CLASS_ID)).thenReturn(Optional.of(CORPUS_AVATAR_ID));
        when(wikiRepository.findByAvatarIdAndSlug(CORPUS_AVATAR_ID, PAGE_SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");

        verify(contentGenerator, never()).regenerateAsDraft(any(), any(), any(), any());
    }

    @Test
    void regenerate_noModuleYet_throws409() {
        when(orgClassRepository.findOrganizationIdByClassId(CLASS_ID)).thenReturn(Optional.of(ORG_ID));
        when(orgClassRepository.findCorpusAvatarIdByClassId(CLASS_ID)).thenReturn(Optional.of(CORPUS_AVATAR_ID));
        when(wikiRepository.findByAvatarIdAndSlug(CORPUS_AVATAR_ID, PAGE_SLUG)).thenReturn(Optional.of(stubPage()));
        when(avatarRepository.findById(CORPUS_AVATAR_ID)).thenReturn(Optional.of(stubAvatar()));
        when(moduleRepository.findByAvatarIdAndWikiPageSlug(CORPUS_AVATAR_ID, PAGE_SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regeneratePageContent(USER_ID, ORG_ID, CLASS_ID, PAGE_SLUG, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("build lessons first");

        verify(contentGenerator, never()).regenerateAsDraft(any(), any(), any(), any());
    }
}
