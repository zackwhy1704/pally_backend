package com.pally.domain.report;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.infrastructure.config.AdminEmailService;
import com.pally.infrastructure.email.EmailService;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmitContentReportUseCaseTest {

    @Mock ContentReportRepository reportRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock EmailService emailService;
    @Mock AdminEmailService adminEmailService;
    @InjectMocks SubmitContentReportUseCase useCase;

    private static final String AVATAR = "av1";
    private static final String OWNER = "user1";
    private static final String MOCHI_TEXT = "Here is something Mochi said that was reported.";

    private void avatarOwnedBy(String ownerId) {
        Avatar avatar = mock(Avatar.class);
        lenient().when(avatar.getUserId()).thenReturn(ownerId);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(avatar));
    }

    @Test
    void submit_persistsReport_thenEmailsEveryAdmin_withVerbatimMessageText() {
        avatarOwnedBy(OWNER);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(adminEmailService.recipients()).thenReturn(Set.of("a@x.com", "b@x.com"));

        useCase.submit(AVATAR, OWNER, ContentReportReason.UNSAFE, "scary", MOCHI_TEXT, "tutor-123");

        // Persisted with the report's actual fields (reason + verbatim text + best-effort client id).
        ArgumentCaptor<ContentReport> saved = ArgumentCaptor.forClass(ContentReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().reason()).isEqualTo(ContentReportReason.UNSAFE);
        assertThat(saved.getValue().messageText()).isEqualTo(MOCHI_TEXT);
        assertThat(saved.getValue().comment()).isEqualTo("scary");
        assertThat(saved.getValue().clientMessageId()).isEqualTo("tutor-123");

        // Emailed to EACH admin, with the reported text carried into the body.
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(2)).sendHtml(anyString(), anyString(), html.capture());
        assertThat(html.getValue()).contains(MOCHI_TEXT);
    }

    @Test
    void submit_emailThrows_reportStillPersisted_andNoExceptionPropagates() {
        // Best-effort email: persist is the record of truth. Fail-without-fix — without the
        // try/catch around sendHtml, this would throw and the caller would see a failed report.
        avatarOwnedBy(OWNER);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(adminEmailService.recipients()).thenReturn(Set.of("a@x.com"));
        doThrow(new RuntimeException("Resend down")).when(emailService).sendHtml(anyString(), anyString(), anyString());

        assertThatCode(() -> useCase.submit(AVATAR, OWNER, ContentReportReason.OTHER, null, MOCHI_TEXT, null))
                .doesNotThrowAnyException();

        verify(reportRepository).save(any());
    }

    @Test
    void submit_noAdminsConfigured_stillPersists_neverEmails_neverThrows() {
        avatarOwnedBy(OWNER);
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(adminEmailService.recipients()).thenReturn(Set.of());

        assertThatCode(() -> useCase.submit(AVATAR, OWNER, ContentReportReason.WRONG_OR_MISLEADING, null, MOCHI_TEXT, null))
                .doesNotThrowAnyException();

        verify(reportRepository).save(any());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void submit_avatarNotOwnedByCaller_throws_andPersistsNothing() {
        // Ownership guard (404, not 403): another user's avatar id must not let you file a report
        // against it — and nothing is persisted or emailed when it fails.
        Avatar someoneElses = mock(Avatar.class);
        when(someoneElses.getUserId()).thenReturn("other-user");
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> useCase.submit(AVATAR, OWNER, ContentReportReason.UNSAFE, null, MOCHI_TEXT, null))
                .isInstanceOf(AvatarNotFoundException.class);

        verify(reportRepository, never()).save(any());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void submit_avatarMissing_throws_andPersistsNothing() {
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.submit(AVATAR, OWNER, ContentReportReason.UNSAFE, null, MOCHI_TEXT, null))
                .isInstanceOf(AvatarNotFoundException.class);

        verify(reportRepository, never()).save(any());
    }
}
