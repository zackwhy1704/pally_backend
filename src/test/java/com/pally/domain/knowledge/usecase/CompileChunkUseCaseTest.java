package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.subscription.ChunkCompileGuard;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Picking a chapter to compile: the guard gates it (402 over allowance), a pick
 * flips PENDING_CHUNK → READY and schedules the compile, and the action is
 * idempotent + never double-charges. Ownership is enforced.
 */
@ExtendWith(MockitoExtension.class)
class CompileChunkUseCaseTest {

    @Mock KnowledgeRepository knowledgeRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock ChunkCompileGuard chunkCompileGuard;
    @Mock WikiRecompileScheduler recompileScheduler;
    @Mock ConsentGuard consentGuard;
    @Mock AvatarSlotGuard avatarSlotGuard;

    private CompileChunkUseCase useCase;

    private static final String USER = "u1";
    private static final String AVATAR = "av1";

    @BeforeEach
    void setUp() {
        useCase = new CompileChunkUseCase(knowledgeRepository, avatarRepository,
                chunkCompileGuard, recompileScheduler, consentGuard, avatarSlotGuard);
        lenient().when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(
                Avatar.reconstitute(AVATAR, USER, "Mochi", Subject.MATHS, CharacterType.MOCHI, 1,
                        java.time.Instant.now())));
        lenient().when(knowledgeRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(chunkCompileGuard.allowance(anyString()))
                .thenReturn(new ChunkCompileGuard.ChunkAllowance(1, 5));
    }

    private KnowledgeFile chunk(KnowledgeFile.Status status, String compiledBy) {
        KnowledgeFile parent = KnowledgeFile.create(AVATAR, USER, "book.pdf", "k/b.pdf",
                KnowledgeFile.UploadType.PDF);
        KnowledgeFile c = KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "slice");
        if (status == KnowledgeFile.Status.READY) c.markPicked();
        if (compiledBy != null) c.markCompiled(compiledBy);
        return c;
    }

    @Test
    void pickPending_checksGuard_flipsReady_schedulesCompile() {
        KnowledgeFile c = chunk(KnowledgeFile.Status.PENDING_CHUNK, null);
        when(knowledgeRepository.findById("c1")).thenReturn(Optional.of(c));

        CompileChunkUseCase.Result r = useCase.execute(USER, AVATAR, "c1");

        verify(chunkCompileGuard).requireChunkCompileQuota(USER);
        assertThat(c.getStatus()).isEqualTo(KnowledgeFile.Status.READY);
        verify(recompileScheduler).requestRecompile(AVATAR);
        assertThat(r.allowance().limit()).isEqualTo(5);
    }

    @Test
    void overAllowance_throws402_leavesChunkPending_noCompile() {
        KnowledgeFile c = chunk(KnowledgeFile.Status.PENDING_CHUNK, null);
        when(knowledgeRepository.findById("c1")).thenReturn(Optional.of(c));
        doThrow(new UpgradeRequiredException("CHUNK_COMPILE"))
                .when(chunkCompileGuard).requireChunkCompileQuota(USER);

        assertThatThrownBy(() -> useCase.execute(USER, AVATAR, "c1"))
                .isInstanceOf(UpgradeRequiredException.class);
        assertThat(c.getStatus()).isEqualTo(KnowledgeFile.Status.PENDING_CHUNK); // not picked
        verify(recompileScheduler, never()).requestRecompile(anyString());
    }

    @Test
    void alreadyCompiled_isNoOp_noGuardNoCompile() {
        KnowledgeFile c = chunk(KnowledgeFile.Status.READY, "gemini-2.5-flash");
        when(knowledgeRepository.findById("c1")).thenReturn(Optional.of(c));

        useCase.execute(USER, AVATAR, "c1");

        verify(chunkCompileGuard, never()).requireChunkCompileQuota(anyString());
        verify(recompileScheduler, never()).requestRecompile(anyString());
    }

    @Test
    void pickedButNotCompiled_reTriggers_withoutReCharging() {
        KnowledgeFile c = chunk(KnowledgeFile.Status.READY, null); // READY, compiled_by null
        when(knowledgeRepository.findById("c1")).thenReturn(Optional.of(c));

        useCase.execute(USER, AVATAR, "c1");

        verify(chunkCompileGuard, never()).requireChunkCompileQuota(anyString()); // no re-charge
        verify(recompileScheduler).requestRecompile(AVATAR);                       // but re-tries
    }

    @Test
    void avatarNotOwned_throwsNotFound() {
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(
                Avatar.reconstitute(AVATAR, "someone-else", "Mochi", Subject.MATHS,
                        CharacterType.MOCHI, 1, java.time.Instant.now())));

        assertThatThrownBy(() -> useCase.execute(USER, AVATAR, "c1"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void nonChunkFile_throws404() {
        when(knowledgeRepository.findById("plain"))
                .thenReturn(Optional.of(KnowledgeFile.create(AVATAR, USER, "f.pdf", "k", KnowledgeFile.UploadType.PDF)));

        assertThatThrownBy(() -> useCase.execute(USER, AVATAR, "plain"))
                .isInstanceOf(BusinessException.class);
    }
}
