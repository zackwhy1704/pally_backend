package com.pally.domain.knowledge;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.persistence.knowledge.WikiConflictJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiConflictJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part A: a detected conflict becomes a teacher review entry; resolve sets the
 * canonical content and locks the page (RESOLVED row) so a recompile can't silently
 * overwrite it. v1 keeps the newest value live for students — no quarantine here.
 */
@ExtendWith(MockitoExtension.class)
class WikiConflictServiceTest {

    @Mock WikiConflictJpaRepository conflictRepo;
    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;

    private WikiConflictService service;

    private static final String AV = "av-1";
    private static final String USER = "teacher-1";

    @BeforeEach
    void setUp() {
        service = new WikiConflictService(conflictRepo, wikiRepository, avatarRepository);
    }

    @Test
    void open_createsAnEntry_withConfidence_andDeterministicNote() {
        when(conflictRepo.existsByAvatarIdAndSlugAndStatus(AV, "mitochondria", "OPEN")).thenReturn(false);

        service.open(AV, "mitochondria", "produces 38 ATP", "produces 36 ATP",
                "produces atp: 38 vs 36", "DETERMINISTIC");

        ArgumentCaptor<WikiConflictJpaEntity> cap = ArgumentCaptor.forClass(WikiConflictJpaEntity.class);
        verify(conflictRepo).save(cap.capture());
        WikiConflictJpaEntity saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getConfidence()).isEqualTo("DETERMINISTIC");
        assertThat(saved.getNote()).contains("38").contains("36");
    }

    @Test
    void open_isIdempotentPerSlug_noDuplicateWhenOneAlreadyOpen() {
        when(conflictRepo.existsByAvatarIdAndSlugAndStatus(AV, "mitochondria", "OPEN")).thenReturn(true);

        service.open(AV, "mitochondria", "a", "b", "note", "DETERMINISTIC");

        verify(conflictRepo, never()).save(any());
    }

    @Test
    void resolve_setsCanonicalContent_marksResolved_andLocksThePage() {
        Avatar owner = Avatar.create(USER, "Corpus", Subject.SCIENCE, CharacterType.MOCHI);
        when(avatarRepository.findById(AV)).thenReturn(Optional.of(owner));

        WikiConflictJpaEntity c = new WikiConflictJpaEntity();
        c.setId("c1");
        c.setAvatarId(AV);
        c.setSlug("mitochondria");
        c.setStatus("OPEN");
        when(conflictRepo.findById("c1")).thenReturn(Optional.of(c));

        WikiPage page = WikiPage.create(AV, "mitochondria", "Mitochondria", "produces 38 ATP");
        when(wikiRepository.findByAvatarIdAndSlug(AV, "mitochondria")).thenReturn(Optional.of(page));
        when(wikiRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolve(AV, "c1", USER, "produces 36 ATP");

        // page content becomes the chosen canonical value
        ArgumentCaptor<WikiPage> pageCap = ArgumentCaptor.forClass(WikiPage.class);
        verify(wikiRepository).save(pageCap.capture());
        assertThat(pageCap.getValue().getContent()).isEqualTo("produces 36 ATP");
        // conflict marked RESOLVED (the durable page lock)
        assertThat(c.getStatus()).isEqualTo("RESOLVED");
        assertThat(c.getResolvedBy()).isEqualTo(USER);
        verify(conflictRepo).save(c);
    }

    @Test
    void resolve_rejectsNonOwner() {
        Avatar owner = Avatar.create("someone-else", "Corpus", Subject.SCIENCE, CharacterType.MOCHI);
        when(avatarRepository.findById(AV)).thenReturn(Optional.of(owner));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.resolve(AV, "c1", USER, "x"))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);
        verify(conflictRepo, never()).save(any());
    }

    @Test
    void isResolvedLocked_reflectsAResolvedRow() {
        when(conflictRepo.existsByAvatarIdAndSlugAndStatus(AV, "mitochondria", "RESOLVED")).thenReturn(true);
        assertThat(service.isResolvedLocked(AV, "mitochondria")).isTrue();
    }

    @Test
    void listOpen_requiresOwnership_andReturnsQueue() {
        Avatar owner = Avatar.create(USER, "Corpus", Subject.SCIENCE, CharacterType.MOCHI);
        when(avatarRepository.findById(AV)).thenReturn(Optional.of(owner));
        WikiConflictJpaEntity c = new WikiConflictJpaEntity();
        c.setId("c1"); c.setAvatarId(AV); c.setSlug("s"); c.setConfidence("DETERMINISTIC");
        c.setStatus("OPEN"); c.setCreatedAt(Instant.now());
        when(conflictRepo.findOpenForQueue(AV)).thenReturn(List.of(c));

        var queue = service.listOpen(AV, USER);

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).confidence()).isEqualTo("DETERMINISTIC");
    }
}
