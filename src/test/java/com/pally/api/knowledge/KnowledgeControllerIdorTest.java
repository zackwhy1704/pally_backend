package com.pally.api.knowledge;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CheckRelevanceUseCase;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.domain.knowledge.usecase.DeleteFileUseCase;
import com.pally.domain.knowledge.usecase.UploadFileUseCase;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Verifies that wiki page endpoints enforce avatar ownership (IDOR fix).
 *
 * <p>Rule: any authenticated user who knows an avatarId must NOT be able to
 * read or modify another user's wiki pages. AvatarNotFoundException (→ HTTP 404)
 * is the correct response — no existence leak.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerIdorTest {

    @Mock UploadFileUseCase uploadFileUseCase;
    @Mock DeleteFileUseCase deleteFileUseCase;
    @Mock CheckRelevanceUseCase checkRelevanceUseCase;
    @Mock CompileWikiUseCase compileWikiUseCase;
    @Mock com.pally.domain.knowledge.usecase.CompileJobStore compileJobStore;
    @Mock WikiRecompileScheduler recompileScheduler;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock KnowledgeMapper knowledgeMapper;
    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock AvatarSlotGuard avatarSlotGuard;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock WikiPageResponseMapper wikiPageResponseMapper;

    KnowledgeController controller;

    private static final String OWNER_USER    = "user-alice";
    private static final String ATTACKER_USER = "user-bob";
    private static final String AVATAR_ID     = "avatar-123";
    private static final String SLUG          = "photosynthesis";

    private Avatar ownerAvatar;
    private WikiPage wikiPage;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeController(
                uploadFileUseCase,
                deleteFileUseCase,
                checkRelevanceUseCase,
                compileWikiUseCase,
                compileJobStore,
                recompileScheduler,
                knowledgeRepository,
                knowledgeMapper,
                wikiRepository,
                avatarRepository,
                avatarSlotGuard,
                wikiPageSourceRepo,
                wikiPageResponseMapper
        );

        ownerAvatar = Avatar.create(OWNER_USER, "Zap", Subject.SCIENCE, CharacterType.ZAP);

        wikiPage = WikiPage.create(AVATAR_ID, SLUG, "Photosynthesis",
                "Plants use sunlight to make food.");
    }

    // ── getWikiPage — ownership guard ──────────────────────────────────────

    @Test
    void getWikiPage_owner_returns200WithPage() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, SLUG))
                .thenReturn(Optional.of(wikiPage));

        ResponseEntity<?> response = controller.getWikiPage(OWNER_USER, AVATAR_ID, SLUG);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void getWikiPage_differentUser_throws404NotContent() {
        // avatarRepository returns an avatar owned by OWNER_USER, not ATTACKER_USER
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() -> controller.getWikiPage(ATTACKER_USER, AVATAR_ID, SLUG))
                .isInstanceOf(AvatarNotFoundException.class)
                .hasMessageContaining(AVATAR_ID);
    }

    @Test
    void getWikiPage_unknownAvatarId_throws404() {
        when(avatarRepository.findById("unknown-avatar")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getWikiPage(ATTACKER_USER, "unknown-avatar", SLUG))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── applyCorrection — ownership guard ──────────────────────────────────

    @Test
    void applyCorrection_owner_appliesCorrectionAndReturns200() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, SLUG))
                .thenReturn(Optional.of(wikiPage));
        when(wikiRepository.save(wikiPage)).thenReturn(wikiPage);

        var request = new KnowledgeController.HumanCorrectionRequest("Chlorophyll absorbs light.");
        ResponseEntity<?> response =
                controller.applyCorrection(OWNER_USER, AVATAR_ID, SLUG, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void applyCorrection_differentUser_throws404NotOwnerData() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));

        var request = new KnowledgeController.HumanCorrectionRequest("Malicious edit");

        assertThatThrownBy(() -> controller.applyCorrection(ATTACKER_USER, AVATAR_ID, SLUG, request))
                .isInstanceOf(AvatarNotFoundException.class)
                .hasMessageContaining(AVATAR_ID);
    }

    @Test
    void applyCorrection_unknownAvatarId_throws404() {
        when(avatarRepository.findById("ghost")).thenReturn(Optional.empty());

        var request = new KnowledgeController.HumanCorrectionRequest("edit");

        assertThatThrownBy(() -> controller.applyCorrection(ATTACKER_USER, "ghost", SLUG, request))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── listFiles — ownership guard ─────────────────────────────────────────

    @Test
    void listFiles_owner_returns200() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(knowledgeRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(knowledgeMapper.toResponseList(List.of())).thenReturn(List.of());

        ResponseEntity<?> response = controller.listFiles(OWNER_USER, AVATAR_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void listFiles_differentUser_throws404() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() -> controller.listFiles(ATTACKER_USER, AVATAR_ID))
                .isInstanceOf(AvatarNotFoundException.class);
    }
}
