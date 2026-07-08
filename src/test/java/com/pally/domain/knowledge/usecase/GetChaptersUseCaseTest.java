package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.subscription.ChunkCompileGuard;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * The chapters read behind the picker + locked-chapter surface: it lists chunks with
 * their compile STATE (LOCKED/COMPILING/COMPILED), exposes the allowance counter from
 * a single source, and enforces ownership.
 */
@ExtendWith(MockitoExtension.class)
class GetChaptersUseCaseTest {

    @Mock KnowledgeRepository knowledgeRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock ChunkCompileGuard chunkCompileGuard;

    private GetChaptersUseCase useCase;

    private static final String USER = "u1";
    private static final String AVATAR = "av1";

    @BeforeEach
    void setUp() {
        useCase = new GetChaptersUseCase(knowledgeRepository, avatarRepository, chunkCompileGuard);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(
                Avatar.reconstitute(AVATAR, USER, "Mochi", Subject.MATHS, CharacterType.MOCHI, 1, Instant.now())));
    }

    @Test
    void listsChunksWithState_andAllowance_fromOneSource() {
        KnowledgeFile parent = KnowledgeFile.create(AVATAR, USER, "book.pdf", "k", KnowledgeFile.UploadType.PDF);
        parent.markSegmented();
        KnowledgeFile locked = KnowledgeFile.createChunk(parent, "Ch 1", 1, 25, 25, "a");
        KnowledgeFile compiling = KnowledgeFile.createChunk(parent, "Ch 2", 26, 50, 25, "b");
        compiling.markPicked();
        KnowledgeFile compiled = KnowledgeFile.createChunk(parent, "Ch 3", 51, 75, 25, "c");
        compiled.markPicked();
        compiled.markCompiled("gemini-2.5-flash");

        when(knowledgeRepository.findByAvatarId(AVATAR)).thenReturn(List.of(parent));
        when(knowledgeRepository.findByParentFileId(parent.getId()))
                .thenReturn(List.of(locked, compiling, compiled));
        when(chunkCompileGuard.allowance(USER)).thenReturn(new ChunkCompileGuard.ChunkAllowance(1, 5));

        GetChaptersUseCase.ChaptersResult r = useCase.execute(USER, AVATAR);

        assertThat(r.allowanceUsed()).isEqualTo(1);
        assertThat(r.allowanceLimit()).isEqualTo(5);
        assertThat(r.chapters()).extracting(GetChaptersUseCase.Chapter::state)
                .containsExactly("LOCKED", "COMPILING", "COMPILED");
        assertThat(r.chapters().get(0).title()).isEqualTo("Ch 1");
        assertThat(r.chapters().get(0).parentFileId()).isEqualTo(parent.getId());
    }

    @Test
    void nonSegmentedFiles_produceNoChapters() {
        KnowledgeFile plain = KnowledgeFile.create(AVATAR, USER, "notes.pdf", "k", KnowledgeFile.UploadType.PDF);
        plain.markReady(3);
        when(knowledgeRepository.findByAvatarId(AVATAR)).thenReturn(List.of(plain));
        when(chunkCompileGuard.allowance(USER)).thenReturn(new ChunkCompileGuard.ChunkAllowance(0, 5));

        assertThat(useCase.execute(USER, AVATAR).chapters()).isEmpty();
    }

    @Test
    void notOwned_throwsNotFound() {
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(
                Avatar.reconstitute(AVATAR, "other", "Mochi", Subject.MATHS, CharacterType.MOCHI, 1, Instant.now())));
        assertThatThrownBy(() -> useCase.execute(USER, AVATAR)).isInstanceOf(AvatarNotFoundException.class);
    }
}
