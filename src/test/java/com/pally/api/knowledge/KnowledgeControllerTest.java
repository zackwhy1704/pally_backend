package com.pally.api.knowledge;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CheckRelevanceUseCase;
import com.pally.domain.knowledge.usecase.CompileJobStore;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.domain.knowledge.usecase.DeleteFileUseCase;
import com.pally.domain.knowledge.usecase.UploadFileUseCase;
import com.pally.domain.knowledge.usecase.UploadResult;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KnowledgeController covering upload, relevance, and compile endpoints.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @Mock private UploadFileUseCase uploadFileUseCase;
    @Mock private DeleteFileUseCase deleteFileUseCase;
    @Mock private CheckRelevanceUseCase checkRelevanceUseCase;
    @Mock private CompileWikiUseCase compileWikiUseCase;
    @Mock private CompileJobStore compileJobStore;
    @Mock private WikiRecompileScheduler recompileScheduler;
    @Mock private KnowledgeRepository knowledgeRepository;
    @Mock private KnowledgeMapper knowledgeMapper;
    @Mock private WikiRepository wikiRepository;
    @Mock private AvatarRepository avatarRepository;
    @Mock private AvatarSlotGuard avatarSlotGuard;
    @Mock private WikiPageSourceJpaRepository wikiPageSourceRepo;

    @InjectMocks
    private KnowledgeController controller;

    @Test
    void uploadFile_validFile_returns201() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "Math content".getBytes());
        when(uploadFileUseCase.execute(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Success("file-1", 1, List.of("fractions")));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void uploadFile_emptyFile_returns400() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        var response = controller.uploadFile("user-1", "avatar-1", emptyFile, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadFile_nullFile_returns400() {
        var response = controller.uploadFile("user-1", "avatar-1", null, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadFile_oversized_returns413() {
        byte[] oversized = new byte[26 * 1024 * 1024]; // > 25MB
        MockMultipartFile bigFile = new MockMultipartFile(
                "file", "big.txt", "text/plain", oversized);

        var response = controller.uploadFile("user-1", "avatar-1", bigFile, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void uploadFile_relevanceWarning_returns200() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "recipe.txt", "text/plain", "Pasta recipe".getBytes());
        when(uploadFileUseCase.execute(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.RelevanceWarning("file-1", 0.15, "Off topic"));

        var response = controller.uploadFile("user-1", "avatar-1", file, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void listFiles_avatarNotOwned_throwsAvatarNotFound() {
        Avatar otherAvatar = Avatar.create("other-user", "Test", Subject.MATHS, CharacterType.MOCHI);
        when(avatarRepository.findById("avatar-1"))
                .thenReturn(Optional.of(otherAvatar));

        assertThatThrownBy(() -> controller.listFiles("user-1", "avatar-1"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void listFiles_validOwner_returns200() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));
        when(knowledgeRepository.findByAvatarId(avatar.getId())).thenReturn(List.of());
        when(knowledgeMapper.toResponseList(anyList())).thenReturn(List.of());

        var response = controller.listFiles("user-1", avatar.getId());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void compileStatus_noJobFound_returnsNone() {
        when(compileJobStore.findByAvatarId("avatar-1")).thenReturn(null);

        var response = controller.compileStatus("user-1", "avatar-1");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().get("state")).isEqualTo("NONE");
    }

    @Test
    void relevanceCheck_avatarNotOwned_throwsAvatarNotFound() {
        Avatar otherAvatar = Avatar.create("other-user", "Test", Subject.MATHS, CharacterType.MOCHI);
        when(avatarRepository.findById("avatar-1"))
                .thenReturn(Optional.of(otherAvatar));

        com.pally.api.knowledge.dto.RelevanceCheckRequest request =
                new com.pally.api.knowledge.dto.RelevanceCheckRequest("sample text");

        assertThatThrownBy(() ->
                controller.checkRelevance("user-1", "avatar-1", request))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getWikiPage_pageNotFound_throwsBusinessException() {
        Avatar avatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);
        when(avatarRepository.findById(avatar.getId())).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarIdAndSlug(avatar.getId(), "nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                controller.getWikiPage("user-1", avatar.getId(), "nonexistent"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Wiki page not found");
    }
}
