package com.pally.domain.centre;

import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgSubscriptionServiceTest {

    @Mock OrganizationJpaRepository orgRepo;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock CacheManager cacheManager;

    @InjectMocks OrgSubscriptionService service;

    private static final String ORG_ID = "org-1";

    private OrganizationJpaEntity org() {
        OrganizationJpaEntity o = new OrganizationJpaEntity();
        o.setId(ORG_ID);
        o.setName("ABC Learning");
        return o;
    }

    @Test
    void startPilot_setsCorrectCap_forCentreTier() {
        OrganizationJpaEntity o = org();
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(o));
        when(cacheManager.getCache(anyString())).thenReturn(null); // no cache to evict

        service.startPilot(ORG_ID, "CENTRE", 3);

        ArgumentCaptor<OrganizationJpaEntity> captor = ArgumentCaptor.forClass(OrganizationJpaEntity.class);
        verify(orgRepo).save(captor.capture());
        OrganizationJpaEntity saved = captor.getValue();

        assertThat(saved.getTier()).isEqualTo("CENTRE");
        assertThat(saved.getSubStatus()).isEqualTo(OrganizationJpaEntity.STATUS_PILOT);
        assertThat(saved.getClassStudentCap()).isEqualTo(20);  // CAP_CENTRE
        assertThat(saved.getClassLimit()).isEqualTo(3);
        assertThat(saved.getPilotEndsAt()).isAfter(Instant.now());
    }

    @Test
    void startPilot_setsCorrectCap_forSoloTier() {
        OrganizationJpaEntity o = org();
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(o));
        when(cacheManager.getCache(anyString())).thenReturn(null);

        service.startPilot(ORG_ID, "SOLO", 1);

        ArgumentCaptor<OrganizationJpaEntity> captor = ArgumentCaptor.forClass(OrganizationJpaEntity.class);
        verify(orgRepo).save(captor.capture());
        assertThat(captor.getValue().getClassStudentCap()).isEqualTo(15);  // CAP_SOLO
    }

    @Test
    void startPilot_whenAlreadyActive_throwsConflict() {
        OrganizationJpaEntity o = org();
        o.setSubStatus(OrganizationJpaEntity.STATUS_ACTIVE);
        when(orgRepo.findById(ORG_ID)).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> service.startPilot(ORG_ID, "CENTRE", 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void isEntitled_activePilot_returnsTrue() {
        OrganizationJpaEntity o = org();
        o.setSubStatus(OrganizationJpaEntity.STATUS_PILOT);
        o.setPilotEndsAt(Instant.now().plus(Duration.ofDays(10)));

        assertThat(service.isEntitled(o)).isTrue();
    }

    @Test
    void isEntitled_pilotExpired_returnsFalseAndFlipsStatus() {
        OrganizationJpaEntity o = org();
        o.setSubStatus(OrganizationJpaEntity.STATUS_PILOT);
        o.setPilotEndsAt(Instant.now().minus(Duration.ofDays(1)));
        when(cacheManager.getCache(anyString())).thenReturn(null);

        boolean entitled = service.isEntitled(o);

        assertThat(entitled).isFalse();
        assertThat(o.getSubStatus()).isEqualTo(OrganizationJpaEntity.STATUS_EXPIRED);
        verify(orgRepo).save(o);
    }

    @Test
    void isEntitled_activeOrg_returnsTrue() {
        OrganizationJpaEntity o = org();
        o.setSubStatus(OrganizationJpaEntity.STATUS_ACTIVE);

        assertThat(service.isEntitled(o)).isTrue();
    }

    @Test
    void isEntitled_canceledOrg_returnsFalse() {
        OrganizationJpaEntity o = org();
        o.setSubStatus(OrganizationJpaEntity.STATUS_CANCELED);

        assertThat(service.isEntitled(o)).isFalse();
    }
}
