package com.pally.api.chat;

import com.pally.api.chat.dto.ContentReportRequest;
import com.pally.domain.report.SubmitContentReportUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Report an inappropriate/wrong AI (Mochi) chat message. Distinct from the chat feedback endpoint:
 * feedback is a quality rating; this is a child-safety report that reaches a human (admin email).
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/chat")
@RequiredArgsConstructor
public class ContentReportController {

    private final SubmitContentReportUseCase submitContentReport;

    @PostMapping("/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody ContentReportRequest request
    ) {
        submitContentReport.submit(avatarId, userId, request.reason(),
                request.comment(), request.messageText(), request.clientMessageId());
    }
}
