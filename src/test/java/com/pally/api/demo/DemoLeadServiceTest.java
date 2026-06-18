package com.pally.api.demo;

import com.pally.domain.demo.DemoLeadService;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.demo.DemoLeadJpaEntity;
import com.pally.infrastructure.persistence.demo.DemoLeadJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoLeadServiceTest {

    @Mock EmailService emailService;
    @Mock DemoLeadJpaRepository leadRepo;

    @InjectMocks DemoLeadService service;

    @BeforeEach
    void setUp() {
        // @Value fields are not injected by Mockito — wire them manually.
        ReflectionTestUtils.setField(service, "leadsEmail", "hello@apalchi.com");
        // leadRepo.save() returns the saved entity; use lenient so validation-throwing
        // tests don't trigger UnnecessaryStubbingException.
        lenient().when(leadRepo.save(any(DemoLeadJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static final String ORG   = "ABC Learning Centre";
    private static final String NAME  = "Jane Smith";
    private static final String EMAIL = "jane@abc.edu";
    private static final String PHONE = "+6591234567";

    @Test
    void submitLead_validLead_emailsLeadsInbox() {
        service.submitLead(ORG, NAME, EMAIL, PHONE);
        // Default leads address is hello@apalchi.com when env var absent.
        verify(emailService).sendHtml(eq("hello@apalchi.com"), contains(ORG), anyString());
    }

    @Test
    void submitLead_validLead_emailsRequesterConfirmation() {
        service.submitLead(ORG, NAME, EMAIL, PHONE);
        verify(emailService).sendHtml(eq(EMAIL), anyString(), anyString());
    }

    @Test
    void submitLead_blankOrgName_throws400() {
        assertThatThrownBy(() -> service.submitLead("  ", NAME, EMAIL, PHONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Organisation");
    }

    @Test
    void submitLead_blankContactName_throws400() {
        assertThatThrownBy(() -> service.submitLead(ORG, "", EMAIL, PHONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Contact name");
    }

    @Test
    void submitLead_invalidEmail_throws400() {
        assertThatThrownBy(() -> service.submitLead(ORG, NAME, "not-an-email", PHONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("email");
    }

    @Test
    void submitLead_blankPhone_throws400() {
        assertThatThrownBy(() -> service.submitLead(ORG, NAME, EMAIL, ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Contact number");
    }

    @Test
    void submitLead_persistsLeadRow_withCorrectFields() {
        service.submitLead(ORG, NAME, EMAIL, PHONE, "CENTRE", 5, 80);

        ArgumentCaptor<DemoLeadJpaEntity> captor = ArgumentCaptor.forClass(DemoLeadJpaEntity.class);
        verify(leadRepo).save(captor.capture());
        DemoLeadJpaEntity saved = captor.getValue();

        assertThat(saved.getOrgName()).isEqualTo(ORG);
        assertThat(saved.getContactName()).isEqualTo(NAME);
        assertThat(saved.getEmail()).isEqualTo(EMAIL.toLowerCase());
        assertThat(saved.getPhone()).isEqualTo(PHONE);
        assertThat(saved.getSegment()).isEqualTo("CENTRE");
        assertThat(saved.getEstClasses()).isEqualTo(5);
        assertThat(saved.getEstStudents()).isEqualTo(80);
        assertThat(saved.getStatus()).isEqualTo(DemoLeadJpaEntity.STATUS_NEW);
        assertThat(saved.getId()).isNotBlank();
    }

    @Test
    void submitLead_invalidSegment_defaultsToCentre() {
        service.submitLead(ORG, NAME, EMAIL, PHONE, "UNKNOWN_SEGMENT", null, null);

        ArgumentCaptor<DemoLeadJpaEntity> captor = ArgumentCaptor.forClass(DemoLeadJpaEntity.class);
        verify(leadRepo).save(captor.capture());
        assertThat(captor.getValue().getSegment()).isEqualTo("CENTRE");
    }

    @Test
    void submitLead_nullSegment_defaultsToCentre() {
        service.submitLead(ORG, NAME, EMAIL, PHONE, null, null, null);

        ArgumentCaptor<DemoLeadJpaEntity> captor = ArgumentCaptor.forClass(DemoLeadJpaEntity.class);
        verify(leadRepo).save(captor.capture());
        assertThat(captor.getValue().getSegment()).isEqualTo("CENTRE");
    }
}
