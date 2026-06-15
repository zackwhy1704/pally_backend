package com.pally.domain.module;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModuleGenerationService} (split out of the former
 * god ModuleService): idempotent module creation from wiki pages.
 */
@ExtendWith(MockitoExtension.class)
class ModuleGenerationServiceTest {

    @Mock private LearningModuleJpaRepository moduleRepository;
    @Mock private ModuleContentGenerator contentGenerator;
    @Mock private AvatarRepository avatarRepository;
    @Mock private WikiRepository wikiRepository;

    private ModuleGenerationService service;

    @BeforeEach
    void setUp() {
        service = new ModuleGenerationService(
                moduleRepository, contentGenerator, avatarRepository, wikiRepository);
    }

    @Test
    void generateModules_avatarNotFound_throws() {
        when(avatarRepository.findById("bad-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generateModules("bad-id"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void generateModules_noWikiPages_throwsNoNotes409() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(avatar.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.generateModules(avatar.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("NO_NOTES");
        verify(contentGenerator, never()).generate(any(), any());
    }

    @Test
    void generateModules_skipsExistingSlugs() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        WikiPage page = WikiPage.create(avatar.getId(), "fractions", "Fractions", "Content");
        when(wikiRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(page));

        when(moduleRepository.findByAvatarIdAndWikiPageSlug(avatar.getId(), "fractions"))
                .thenReturn(Optional.of(new LearningModuleJpaEntity()));

        List<LearningModuleJpaEntity> result = service.generateModules(avatar.getId());
        assertThat(result).isEmpty();
        verify(contentGenerator, never()).generate(any(), any());
    }

    @Test
    void generateModules_createsModuleForNewPage() {
        Avatar avatar = Avatar.create("user1", "Test", Subject.MATHS, CharacterType.ZAP);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));

        WikiPage page = WikiPage.create(avatar.getId(), "fractions", "Fractions", "Content");
        when(wikiRepository.findByAvatarId(avatar.getId())).thenReturn(List.of(page));
        when(moduleRepository.findByAvatarIdAndWikiPageSlug(avatar.getId(), "fractions"))
                .thenReturn(Optional.empty());

        LearningModuleJpaEntity module = new LearningModuleJpaEntity();
        module.setId("mod-1");
        when(contentGenerator.generate(avatar, page)).thenReturn(module);

        List<LearningModuleJpaEntity> result = service.generateModules(avatar.getId());
        assertThat(result).hasSize(1);
        verify(contentGenerator).generate(avatar, page);
    }
}
