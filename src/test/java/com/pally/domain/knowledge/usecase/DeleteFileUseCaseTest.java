package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.infrastructure.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeleteFileUseCaseTest {

    @Mock KnowledgeRepository knowledgeRepository;
    @Mock StorageService storageService;
    @Mock AvatarRepository avatarRepository;
    @Mock CompileWikiUseCase compileWikiUseCase;
    @Mock CacheInvalidationService cacheInvalidationService;
    @Mock CacheKeepAliveService cacheKeepAliveService;

    // Inline executor so async tasks run synchronously in tests.
    private final Executor syncExecutor = Runnable::run;

    DeleteFileUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteFileUseCase(
                knowledgeRepository, storageService, avatarRepository,
                compileWikiUseCase, cacheInvalidationService, cacheKeepAliveService,
                syncExecutor);
    }

    @Test
    void deleteFile_happyPath_deletesStorageAndDbAndTriggersRecompile() {
        KnowledgeFile file = KnowledgeFile.create(
                "avatar-1", "user-1", "circuits.pdf", "key/circuits.pdf",
                KnowledgeFile.UploadType.PDF);

        when(knowledgeRepository.findById(file.getId())).thenReturn(Optional.of(file));
        Avatar avatar = Avatar.create("user-1", "Zap", Subject.SCIENCE, CharacterType.MOCHI);
        when(avatarRepository.findById("avatar-1")).thenReturn(Optional.of(avatar));
        CompileWikiUseCase.CompileResult compileResult =
                new CompileWikiUseCase.CompileResult(1, 0, List.of("Circuits"));
        when(compileWikiUseCase.execute("avatar-1")).thenReturn(compileResult);
        when(avatarRepository.save(any())).thenReturn(avatar);

        useCase.execute(file.getId(), "user-1");

        verify(storageService).delete("key/circuits.pdf");
        verify(knowledgeRepository).deleteById(file.getId());
        verify(compileWikiUseCase).execute("avatar-1");
        verify(avatarRepository).save(argThat(a -> a.getWikiPageCount() == 1));
        verify(cacheInvalidationService).onWikiContentChanged(eq("avatar-1"), any());
    }

    @Test
    void deleteFile_fileNotFound_doesNothing() {
        when(knowledgeRepository.findById("missing")).thenReturn(Optional.empty());

        useCase.execute("missing", "user-1");

        verifyNoInteractions(storageService, compileWikiUseCase);
    }

    @Test
    void deleteFile_wrongUser_doesNotDelete() {
        KnowledgeFile file = KnowledgeFile.create(
                "avatar-1", "user-99", "notes.pdf", "key/notes.pdf",
                KnowledgeFile.UploadType.PDF);
        when(knowledgeRepository.findById(file.getId())).thenReturn(Optional.of(file));

        useCase.execute(file.getId(), "user-1");   // different user

        verifyNoInteractions(storageService, compileWikiUseCase);
        verify(knowledgeRepository, never()).deleteById(any());
    }

    @Test
    void deleteFile_recompileFails_doesNotBubble() {
        KnowledgeFile file = KnowledgeFile.create(
                "avatar-1", "user-1", "notes.pdf", "key/notes.pdf",
                KnowledgeFile.UploadType.PDF);
        when(knowledgeRepository.findById(file.getId())).thenReturn(Optional.of(file));
        when(compileWikiUseCase.execute("avatar-1"))
                .thenThrow(new RuntimeException("Claude down"));

        // Should not throw — recompile failure is logged but swallowed
        useCase.execute(file.getId(), "user-1");

        verify(storageService).delete("key/notes.pdf");
        verify(knowledgeRepository).deleteById(file.getId());
    }
}
