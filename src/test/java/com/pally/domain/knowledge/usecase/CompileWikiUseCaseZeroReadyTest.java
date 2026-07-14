package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Fix B at the source: the zero-READY skip must distinguish an INTENDED empty brain
 * (no files at all → archive, e.g. user deleted their last file) from a TRANSIENT
 * zero-ready (files exist but none READY — a commit-race victim or all-FAILED), where
 * archiving would WIPE an established brain. Only the skip path is exercised (execute
 * returns early), so only the two repositories it touches need mocking.
 */
@ExtendWith(MockitoExtension.class)
class CompileWikiUseCaseZeroReadyTest {

    @Mock AvatarRepository avatarRepository;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock WikiRepository wikiRepository;
    @InjectMocks CompileWikiUseCase useCase;

    @BeforeEach
    void ownerExists() {
        when(avatarRepository.findById("av")).thenReturn(Optional.of(mock(Avatar.class)));
    }

    @Test
    void noFilesAtAll_total0_archivesOrphanPages_theIntendedEmptyBrain() {
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of());

        CompileWikiUseCase.CompileResult r = useCase.execute("av");

        // Delete-last-file behaviour preserved: pages archived, NOT a retry signal.
        verify(wikiRepository).archiveOrphanPages("av", List.of());
        assertThat(r.tierServed()).isNotEqualTo("skipped-zero-ready-retry");
    }

    @Test
    void filesExistButNoneReady_doesNotArchive_signalsSingleRetry() {
        // A non-READY file (create() → PROCESSING) — total > 0, zero READY.
        KnowledgeFile failed = KnowledgeFile.create("av", "u", "f.pdf", "key", KnowledgeFile.UploadType.PDF);
        failed.markFailed(); // → FAILED (still not READY)
        when(knowledgeRepository.findByAvatarId("av")).thenReturn(List.of(failed));

        CompileWikiUseCase.CompileResult r = useCase.execute("av");

        // The brain is NEVER wiped on a transient zero-ready; scheduler retries once.
        assertThat(r.tierServed()).isEqualTo("skipped-zero-ready-retry");
        verify(wikiRepository, never()).archiveOrphanPages(eq("av"), anyList());
    }
}
