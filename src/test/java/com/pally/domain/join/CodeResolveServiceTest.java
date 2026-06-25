package com.pally.domain.join;

import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeResolveServiceTest {

    @Mock private OrgClassJpaRepository classRepo;
    @Mock private OrganizationJpaRepository orgRepo;
    @Mock private StudyGroupJpaRepository groupRepo;
    @InjectMocks private CodeResolveService service;

    @Test
    void resolve_classCode_returnsClassNameAndCentreContext() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setName("Ms Tan's P5 Math");
        cls.setOrganizationId("org-1");
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setName("Sunrise Tuition Centre");
        when(classRepo.findByJoinCode("5K7Q2X")).thenReturn(Optional.of(cls));
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));

        var r = service.resolve("5k7q2x"); // lower-case in → normalised

        assertThat(r.type()).isEqualTo("CLASS");
        assertThat(r.code()).isEqualTo("5K7Q2X");
        assertThat(r.name()).isEqualTo("Ms Tan's P5 Math");
        assertThat(r.context()).isEqualTo("Sunrise Tuition Centre");
    }

    @Test
    void resolve_groupCode_returnsGroupNameWhenNotAClass() {
        StudyGroupJpaEntity grp = new StudyGroupJpaEntity();
        grp.setName("Exam Squad");
        when(classRepo.findByJoinCode("EXAM01")).thenReturn(Optional.empty());
        when(groupRepo.findByInviteCode("EXAM01")).thenReturn(Optional.of(grp));

        var r = service.resolve("EXAM01");

        assertThat(r.type()).isEqualTo("GROUP");
        assertThat(r.name()).isEqualTo("Exam Squad");
        assertThat(r.context()).isNull();
    }

    @Test
    void resolve_unknownCode_throws404() {
        when(classRepo.findByJoinCode("NOPE99")).thenReturn(Optional.empty());
        when(groupRepo.findByInviteCode("NOPE99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("NOPE99"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(404));
    }

    @Test
    void resolve_blankCode_throws400() {
        assertThatThrownBy(() -> service.resolve("  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void resolve_classWithMissingOrg_stillResolvesWithNullContext() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setName("Orphan Class");
        cls.setOrganizationId("org-x");
        when(classRepo.findByJoinCode("ORPH01")).thenReturn(Optional.of(cls));
        lenient().when(orgRepo.findById("org-x")).thenReturn(Optional.empty());

        var r = service.resolve("ORPH01");

        assertThat(r.type()).isEqualTo("CLASS");
        assertThat(r.name()).isEqualTo("Orphan Class");
        assertThat(r.context()).isNull();
    }
}
