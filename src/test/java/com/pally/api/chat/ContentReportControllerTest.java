package com.pally.api.chat;

import com.pally.api.chat.dto.ContentReportRequest;
import com.pally.domain.report.ContentReportReason;
import com.pally.domain.report.SubmitContentReportUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ContentReportControllerTest {

    @Mock SubmitContentReportUseCase submitContentReport;
    @InjectMocks ContentReportController controller;

    @Test
    void report_delegatesToUseCase_withReasonAndVerbatimText() {
        var request = new ContentReportRequest(
                ContentReportReason.UNSAFE, "please check", "the reported mochi text", "tutor-9");

        controller.report("user-1", "avatar-1", request);

        verify(submitContentReport).submit(
                "avatar-1", "user-1", ContentReportReason.UNSAFE, "please check",
                "the reported mochi text", "tutor-9");
    }

    @Test
    void report_isMapped204NoContent() throws Exception {
        // The action is fire-and-return (mirrors the feedback endpoint) — 204, no body.
        ResponseStatus rs = ContentReportController.class
                .getMethod("report", String.class, String.class, ContentReportRequest.class)
                .getAnnotation(ResponseStatus.class);
        assertThat(rs).isNotNull();
        assertThat(rs.value()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
