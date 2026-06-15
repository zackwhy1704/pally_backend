package com.pally.api.chat;

import com.pally.domain.chat.ChatOrchestrationService;
import com.pally.domain.chat.usecase.SendMessageUseCase;
import com.pally.domain.chat.usecase.SolvePhotoQuestionsUseCase;
import com.pally.domain.consent.ConsentGuard;
import com.pally.infrastructure.ratelimit.ChatRateLimiter;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thin unit tests for {@link ChatController}.
 *
 * <p>These verify auth-guard delegation, routing, and HTTP response shape.
 * Business logic and side-effects are tested in
 * {@link com.pally.domain.chat.ChatOrchestrationServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock private ChatOrchestrationService chatOrchestrationService;
    @Mock private SendMessageUseCase       sendMessageUseCase;
    @Mock private SolvePhotoQuestionsUseCase solvePhotoQuestionsUseCase;
    @Mock private ConsentGuard             consentGuard;
    @Mock private ChatRateLimiter          chatRateLimiter;

    @InjectMocks
    private ChatController chatController;

    private static final String USER_ID   = "user-1";
    private static final String AVATAR_ID = "avatar-1";

    // ── GET /chat/history ─────────────────────────────────────────────────

    @Test
    void getChatHistory_delegatesToOrchestrationService() {
        when(chatOrchestrationService.getChatHistory(USER_ID, AVATAR_ID)).thenReturn(List.of());

        var result = chatController.getChatHistory(USER_ID, AVATAR_ID);

        assertThat(result).isEmpty();
        verify(chatOrchestrationService).getChatHistory(USER_ID, AVATAR_ID);
    }

    @Test
    void getChatHistory_orchestrationThrowsAvatarNotFound_propagates() {
        when(chatOrchestrationService.getChatHistory(anyString(), anyString()))
                .thenThrow(new AvatarNotFoundException(AVATAR_ID));

        assertThatThrownBy(() -> chatController.getChatHistory(USER_ID, AVATAR_ID))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── POST /chat/sync ───────────────────────────────────────────────────

    @Test
    void syncMessages_delegatesToOrchestrationService() {
        when(chatOrchestrationService.syncMessages(eq(USER_ID), eq(AVATAR_ID), any()))
                .thenReturn(Map.of("upserted", 3));

        var request = new com.pally.api.chat.dto.ChatSyncRequest(List.of());
        var result = chatController.syncMessages(USER_ID, AVATAR_ID, request);

        assertThat(result.get("upserted")).isEqualTo(3);
        verify(chatOrchestrationService).syncMessages(eq(USER_ID), eq(AVATAR_ID), any());
    }

    // ── GET /chat/history/full ────────────────────────────────────────────

    @Test
    void getFullHistory_delegatesToOrchestrationService() {
        var historyResponse = new com.pally.api.chat.dto.ChatHistoryResponse(List.of());
        when(chatOrchestrationService.getFullHistory(eq(AVATAR_ID), anyInt()))
                .thenReturn(historyResponse);

        var result = chatController.getFullHistory(USER_ID, AVATAR_ID, 50);

        assertThat(result).isNotNull();
        verify(chatOrchestrationService).getFullHistory(AVATAR_ID, 50);
    }

    // ── POST /chat/{messageId}/feedback ───────────────────────────────────

    @Test
    void submitFeedback_delegatesToOrchestrationService() {
        chatController.submitFeedback(USER_ID, AVATAR_ID, "msg-1",
                new com.pally.api.chat.dto.FeedbackRequest("THUMBS_UP"));

        verify(chatOrchestrationService).submitFeedback("msg-1", "THUMBS_UP");
    }

    // ── POST /chat/session-end ────────────────────────────────────────────

    @Test
    void sessionEnd_delegatesToOrchestrationService_andReturnsLevelUpMap() {
        when(chatOrchestrationService.sessionEnd(USER_ID, AVATAR_ID))
                .thenReturn(Map.of("levelledUp", false, "newLevel", 1, "alreadyCreditedToday", false));

        var result = chatController.sessionEnd(USER_ID, AVATAR_ID);

        assertThat(result).containsKey("levelledUp");
        verify(chatOrchestrationService).sessionEnd(USER_ID, AVATAR_ID);
    }

    // ── PATCH /teaching-mode ─────────────────────────────────────────────

    @Test
    void setTeachingMode_validMode_delegatesToOrchestrationService() {
        when(chatOrchestrationService.setTeachingMode(
                eq(USER_ID), eq(AVATAR_ID), any(com.pally.domain.avatar.TeachingMode.class)))
                .thenReturn(Map.of("mode", "TEACHING"));

        var result = chatController.setTeachingMode(USER_ID, AVATAR_ID,
                Map.of("mode", "TEACHING"));

        assertThat(result.get("mode")).isEqualTo("TEACHING");
    }

    @Test
    void setTeachingMode_invalidMode_defaultsToTeaching() {
        when(chatOrchestrationService.setTeachingMode(
                eq(USER_ID), eq(AVATAR_ID), eq(com.pally.domain.avatar.TeachingMode.TEACHING)))
                .thenReturn(Map.of("mode", "TEACHING"));

        var result = chatController.setTeachingMode(USER_ID, AVATAR_ID,
                Map.of("mode", "INVALID_MODE_XYZ"));

        assertThat(result.get("mode")).isEqualTo("TEACHING");
    }
}
