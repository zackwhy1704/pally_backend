package com.pally.domain.weakness;

import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherWeaknessServiceTest {

    @Mock OrgClassRepository orgClassRepository;
    @Mock CentreAccessService centreAccessService;
    @Mock ClassRosterRepository rosterRepository;
    @Mock UserRepository userRepository;
    @Mock WeaknessProfileService weaknessProfileService;

    @org.mockito.InjectMocks TeacherWeaknessService service;

    @Test
    void enforcesOwnerAuthAndMapsRosterToWeakAreas() {
        when(orgClassRepository.findOrganizationIdByClassId("class-1"))
                .thenReturn(Optional.of("org-1"));
        when(orgClassRepository.findSubjectByClassId("class-1"))
                .thenReturn(Optional.of("MATHS"));
        when(weaknessProfileService.isEnabled()).thenReturn(true);
        when(rosterRepository.activeStudentIds("class-1")).thenReturn(List.of("s1", "s2"));
        User u1 = mock(User.class); when(u1.getId()).thenReturn("s1"); when(u1.getDisplayName()).thenReturn("Aisha");
        User u2 = mock(User.class); when(u2.getId()).thenReturn("s2"); when(u2.getDisplayName()).thenReturn("Ben");
        when(userRepository.findAllByIds(any())).thenReturn(List.of(u1, u2));
        // Real weak signal now = weakSlugsFor (slugs), rendered to labels for display.
        when(weaknessProfileService.weakSlugsFor("s1", Subject.MATHS)).thenReturn(List.of("dividing-fractions"));
        when(weaknessProfileService.weakSlugsFor("s2", Subject.MATHS)).thenReturn(List.of());

        Map<String, Object> out = service.perStudentWeakness("teacher-1", "class-1");

        verify(centreAccessService).ensureOwner("teacher-1", "org-1"); // auth enforced
        assertThat(out.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) out.get("students");
        assertThat(students).hasSize(2);
        assertThat(students.get(0)).containsEntry("displayName", "Aisha");
        @SuppressWarnings("unchecked")
        List<String> areas0 = (List<String>) students.get(0).get("weakAreas");
        @SuppressWarnings("unchecked")
        List<String> areas1 = (List<String>) students.get(1).get("weakAreas");
        assertThat(areas0).containsExactly("Dividing Fractions"); // slug "dividing-fractions" → label
        assertThat(areas1).isEmpty();
    }

    @Test
    void returnsEmptyStudentsWhenFlagOff() {
        when(orgClassRepository.findOrganizationIdByClassId("class-1"))
                .thenReturn(Optional.of("org-1"));
        when(orgClassRepository.findSubjectByClassId("class-1"))
                .thenReturn(Optional.of("MATHS"));
        when(weaknessProfileService.isEnabled()).thenReturn(false);

        Map<String, Object> out = service.perStudentWeakness("teacher-1", "class-1");

        assertThat((List<?>) out.get("students")).isEmpty();
        verify(rosterRepository, never()).activeStudentIds(any());
    }

    @Test
    void throws404WhenClassMissing() {
        when(orgClassRepository.findOrganizationIdByClassId("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.perStudentWeakness("teacher-1", "nope"))
                .isInstanceOf(BusinessException.class);
        verify(centreAccessService, never()).ensureOwner(any(), any());
    }
}
