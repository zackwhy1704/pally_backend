package com.pally.domain.marking;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarKind;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.organization.OrganizationRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarkingCorpusServiceTest {

    @Mock private MarkingCorpusRepository markingCorpusRepository;
    @Mock private AvatarRepository avatarRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrgClassRepository orgClassRepository;

    @InjectMocks private MarkingCorpusService service;

    private MarkingCorpus mapping(String avatarId) {
        return new MarkingCorpus("mc-1", "org-1", "MATHS", avatarId, Instant.now());
    }

    @Test
    void resolveOrCreate_whenMappingExists_returnsItAndCreatesNothing() {
        when(markingCorpusRepository.findByOrgIdAndSubject("org-1", "MATHS"))
                .thenReturn(Optional.of(mapping("av-existing")));

        String id = service.resolveOrCreate("org-1", Subject.MATHS);

        assertThat(id).isEqualTo("av-existing");
        verify(avatarRepository, never()).save(any());
        verify(markingCorpusRepository, never()).save(any());
    }

    @Test
    void resolveOrCreate_whenAbsent_createsHiddenMarkingCorpusAvatarAndMapping() {
        when(markingCorpusRepository.findByOrgIdAndSubject("org-1", "MATHS"))
                .thenReturn(Optional.empty());
        when(organizationRepository.findOwnerUserIdById("org-1"))
                .thenReturn(Optional.of("owner-1"));
        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String id = service.resolveOrCreate("org-1", Subject.MATHS);

        ArgumentCaptor<Avatar> avatarCaptor = ArgumentCaptor.forClass(Avatar.class);
        verify(avatarRepository).save(avatarCaptor.capture());
        Avatar created = avatarCaptor.getValue();
        // The brain is a hidden MARKING_CORPUS avatar owned by the org owner.
        assertThat(created.getKind()).isEqualTo(AvatarKind.MARKING_CORPUS);
        assertThat(created.isMarkingCorpus()).isTrue();
        assertThat(created.getUserId()).isEqualTo("owner-1");
        assertThat(created.getSubject()).isEqualTo(Subject.MATHS);
        assertThat(id).isEqualTo(created.getId());

        ArgumentCaptor<MarkingCorpus> mapCaptor = ArgumentCaptor.forClass(MarkingCorpus.class);
        verify(markingCorpusRepository).save(mapCaptor.capture());
        assertThat(mapCaptor.getValue().orgId()).isEqualTo("org-1");
        assertThat(mapCaptor.getValue().subject()).isEqualTo("MATHS");
        assertThat(mapCaptor.getValue().avatarId()).isEqualTo(created.getId());
    }

    @Test
    void resolveOrCreate_whenOrgMissing_throws404() {
        when(markingCorpusRepository.findByOrgIdAndSubject("org-x", "SCIENCE"))
                .thenReturn(Optional.empty());
        when(organizationRepository.findOwnerUserIdById("org-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveOrCreate("org-x", Subject.SCIENCE))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void resolveOrCreate_lostRace_discardsOrphanAndReturnsWinner() {
        when(markingCorpusRepository.findByOrgIdAndSubject("org-1", "MATHS"))
                .thenReturn(Optional.empty())            // first check: absent
                .thenReturn(Optional.of(mapping("av-winner"))); // post-race re-read
        when(organizationRepository.findOwnerUserIdById("org-1"))
                .thenReturn(Optional.of("owner-1"));
        when(avatarRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(markingCorpusRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_marking_corpus_org_subject"));

        String id = service.resolveOrCreate("org-1", Subject.MATHS);

        assertThat(id).isEqualTo("av-winner");
        // The orphan avatar we optimistically created is cleaned up.
        verify(avatarRepository).deleteById(any());
    }

    @Test
    void findAvatarId_readOnly_neverCreates() {
        when(markingCorpusRepository.findByOrgIdAndSubject("org-1", "MATHS"))
                .thenReturn(Optional.of(mapping("av-1")));

        assertThat(service.findAvatarId("org-1", Subject.MATHS)).contains("av-1");
        verify(avatarRepository, never()).save(any());
    }

    @Test
    void findAvatarIdForClass_resolvesOrgAndNormalisedSubject() {
        when(orgClassRepository.findOrganizationIdByClassId("class-1"))
                .thenReturn(Optional.of("org-1"));
        when(orgClassRepository.findSubjectByClassId("class-1"))
                .thenReturn(Optional.of("P5 Math"));   // free-text → MATHS
        when(markingCorpusRepository.findByOrgIdAndSubject("org-1", "MATHS"))
                .thenReturn(Optional.of(mapping("av-1")));

        assertThat(service.findAvatarIdForClass("class-1")).contains("av-1");
    }

    @Test
    void normalizeSubject_mapsFreeTextVariantsToEnum() {
        assertThat(service.normalizeSubject("Maths")).isEqualTo(Subject.MATHS);
        assertThat(service.normalizeSubject("MATHS")).isEqualTo(Subject.MATHS);
        assertThat(service.normalizeSubject("P5 Math")).isEqualTo(Subject.MATHS);
        assertThat(service.normalizeSubject("science")).isEqualTo(Subject.SCIENCE);
        assertThat(service.normalizeSubject("English Language")).isEqualTo(Subject.ENGLISH);
        assertThat(service.normalizeSubject(null)).isEqualTo(Subject.GENERAL);
        assertThat(service.normalizeSubject("Underwater Basket Weaving"))
                .isEqualTo(Subject.GENERAL);
    }
}
