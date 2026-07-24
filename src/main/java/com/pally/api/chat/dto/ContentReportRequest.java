package com.pally.api.chat.dto;

import com.pally.domain.report.ContentReportReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Report an AI (Mochi) chat message. Carries the message TEXT verbatim (not a messageId) so the
 * report is self-contained and independent of whether that message was persisted server-side.
 */
public record ContentReportRequest(
        @NotNull ContentReportReason reason,
        String comment,
        @NotBlank String messageText,
        String clientMessageId
) {}
