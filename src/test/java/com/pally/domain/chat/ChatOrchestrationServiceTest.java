package com.pally.domain.chat;

import com.pally.api.chat.ChatMapper;
import com.pally.domain.chat.dto.ChatMessageResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.chat.port.ChatSessionCachePort;
import com.pally.domain.chat.usecase.ChatFeedbackService;
import com.pally.domain.chat.usecase.ChatHistoryService;
import com.pally.domain.chat.usecase.ChatSyncService;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.XpService;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatOrchestrationService}.
 *
 * <p>No Spring context — all deps are plain Mockito mocks created via {@code mock()} in setUp.
 * Using explicit {@code mock()} instead of {@code @Mock} avoids the Gradle test-worker
 * byte-buddy instrumentation issue that occurs when many test workers share a JVM process.
 * Test names describe the invariant in plain English.
 */
class ChatOrchestrationServiceTest {

    private AvatarRepository    avatarRepository;
    private ChatRepository      chatRepository;
    private ChatMapper          chatMapper;
    private ChatSyncService     chatSyncService;
    private ChatHistoryService  chatHistoryService;
    private ChatFeedbackService chatFeedbackService;
    private ChatSessionCachePort chatSessionCachePort;
    private XpService           xpService;
    private ActivityLogService  activityLogService;
    private BadgeService        badgeService;

    private ChatOrchestrationService service;
    private Avatar ownerAvatar;

    @BeforeEach
    void setUp() {
        avatarRepository    = mock(AvatarRepository.class);
        chatRepository      = mock(ChatRepository.class);
        chatMapper          = mock(ChatMapper.class);
        chatSyncService     = mock(ChatSyncService.class);
        chatHistoryService  = mock(ChatHistoryService.class);
        chatFeedbackService = mock(ChatFeedbackService.class);
        chatSessionCachePort = mock(ChatSessionCachePort.class);
        xpService           = mock(XpService.class);
        activityLogService  = mock(ActivityLogService.class);
        badgeService        = mock(BadgeService.class);

        service = new ChatOrchestrationService(
                avatarRepository, chatRepository, chatMapper,
                chatSyncService, chatHistoryService, chatFeedbackService,
                chatSessionCachePort, xpService, activityLogService, badgeService);

        ownerAvatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);
    }

    // ── getChatHistory ────────────────────────────────────────────────────

    @Test
    void getChatHistory_avatarBelongsToUser_returnsMappedMessages() {
        when(avatarRepository.findById(ownerAvatar.getId())).thenReturn(Optional.of(ownerAvatar));
        when(chatRepository.findByAvatarId(eq(ownerAvatar.getId()), anyInt())).thenReturn(List.of());
        when(chatMapper.toResponseList(any())).thenReturn(List.<ChatMessageResponse>of());

        List<ChatMessageResponse> result = service.getChatHistory("user-1", ownerAvatar.getId());

        assertThat(result).isEmpty();
        verify(chatRepository).findByAvatarId(eq(ownerAvatar.getId()), anyInt());
    }

    @Test
    void getChatHistory_avatarDoesNotExist_throwsAvatarNotFoundException() {
        when(avatarRepository.findById("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getChatHistory("user-1", "no-such"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getChatHistory_avatarBelongsToOtherUser_throwsAvatarNotFoundException() {
        // ownerAvatar belongs to "user-1"; calling with "user-2" should throw
        when(avatarRepository.findById(ownerAvatar.getId())).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() -> service.getChatHistory("user-2", ownerAvatar.getId()))
                .isInstanceOf(AvatarNotFoundException.class);

        verify(chatRepository, never()).findByAvatarId(anyString(), anyInt());
    }

    // ── syncMessages ─────────────────────────────────────────────────────

    @Test
    void syncMessages_delegatesToChatSyncServiceAndWrapsCount() {
        when(chatSyncService.sync(eq("avatar-1"), eq("user-1"), any())).thenReturn(5);

        Map<String, Integer> result = service.syncMessages("user-1", "avatar-1", List.of());

        assertThat(result.get("upserted")).isEqualTo(5);
        verify(chatSyncService).sync("avatar-1", "user-1", List.of());
    }

    // ── submitFeedback ────────────────────────────────────────────────────

    @Test
    void submitFeedback_delegatesToChatFeedbackService() {
        service.submitFeedback("msg-1", "THUMBS_UP");

        verify(chatFeedbackService).submitFeedback("msg-1", "THUMBS_UP");
    }

    // ── sessionStart ──────────────────────────────────────────────────────

    @Test
    void sessionStart_startsKeepaliveOnCache() {
        service.sessionStart("avatar-1");

        verify(chatSessionCachePort).startKeepalive("avatar-1");
    }

    // ── sessionEnd ────────────────────────────────────────────────────────

    @Test
    void sessionEnd_stopsKeepaliveAndAwardsXpAndBadges() {
        var creditResult = new UserRepository.XpResult(100, 1, 1, false);
        var award = new XpService.ChatAward(5, 0, false, creditResult);
        when(xpService.awardForChat("user-1", "avatar-1")).thenReturn(award);

        Map<String, Object> result = service.sessionEnd("user-1", "avatar-1");

        verify(chatSessionCachePort).stopKeepalive("avatar-1");
        verify(activityLogService).log(eq("user-1"), eq("avatar-1"),
                eq(ActivityLogService.TYPE_CHAT), anyInt(), anyInt());
        verify(badgeService).grantFirstAction("user-1", BadgeService.BadgeType.FIRST_CHAT);
        verify(badgeService).checkAndGrantMilestones("user-1");

        assertThat(result).containsKeys("levelledUp", "newLevel", "alreadyCreditedToday");
        assertThat(result.get("levelledUp")).isEqualTo(false);
    }

    @Test
    void sessionEnd_alreadyCreditedToday_skipsActivityLogAndBadges() {
        var creditResult = new UserRepository.XpResult(100, 1, 1, false);
        var award = new XpService.ChatAward(0, 0, true, creditResult);
        when(xpService.awardForChat("user-1", "avatar-1")).thenReturn(award);

        Map<String, Object> result = service.sessionEnd("user-1", "avatar-1");

        verify(activityLogService, never()).log(any(), any(), any(), anyInt(), anyInt());
        verify(badgeService, never()).grantFirstAction(any(), any());
        assertThat(result.get("alreadyCreditedToday")).isEqualTo(true);
    }

    @Test
    void sessionEnd_xpServiceThrows_doesNotSurfaceExceptionToCallerButStillStopsKeepalive() {
        when(xpService.awardForChat(any(), any())).thenThrow(new RuntimeException("DB down"));

        // Must not throw
        Map<String, Object> result = service.sessionEnd("user-1", "avatar-1");

        verify(chatSessionCachePort).stopKeepalive("avatar-1");
        // Graceful defaults
        assertThat(result.get("levelledUp")).isEqualTo(false);
        assertThat(result.get("newLevel")).isEqualTo(0);
    }

    // ── setTeachingMode ───────────────────────────────────────────────────

    @Test
    void setTeachingMode_ownerUpdatesMode_returnsNewMode() {
        when(avatarRepository.findById(ownerAvatar.getId())).thenReturn(Optional.of(ownerAvatar));
        when(avatarRepository.save(any(Avatar.class))).thenReturn(ownerAvatar);

        Map<String, String> result = service.setTeachingMode(
                "user-1", ownerAvatar.getId(), TeachingMode.DIRECT);

        assertThat(result.get("mode")).isEqualTo("DIRECT");
        verify(avatarRepository).save(ownerAvatar);
    }

    @Test
    void setTeachingMode_avatarBelongsToOtherUser_throwsAvatarNotFoundException() {
        when(avatarRepository.findById(ownerAvatar.getId())).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() ->
                service.setTeachingMode("other-user", ownerAvatar.getId(), TeachingMode.DIRECT))
                .isInstanceOf(AvatarNotFoundException.class);

        verify(avatarRepository, never()).save(any());
    }
}
