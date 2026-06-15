package com.pally.api.chat;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.usecase.ChatFeedbackService;
import com.pally.domain.chat.usecase.ChatHistoryService;
import com.pally.domain.chat.usecase.ChatSyncService;
import com.pally.domain.chat.usecase.SendMessageUseCase;
import com.pally.domain.chat.usecase.SolvePhotoQuestionsUseCase;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.XpService;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.infrastructure.ratelimit.ChatRateLimiter;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for ChatController with mocked use cases.
 * Tests error handling and authorization paths.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock private SendMessageUseCase sendMessageUseCase;
    @Mock private SolvePhotoQuestionsUseCase solvePhotoQuestionsUseCase;
    @Mock private ChatRepository chatRepository;
    @Mock private ChatMapper chatMapper;
    @Mock private ChatSyncService chatSyncService;
    @Mock private ChatHistoryService chatHistoryService;
    @Mock private ChatFeedbackService chatFeedbackService;
    @Mock private CacheKeepAliveService cacheKeepAliveService;
    @Mock private ChatRateLimiter chatRateLimiter;
    @Mock private AvatarRepository avatarRepository;
    @Mock private UserRepository userRepository;
    @Mock private ActivityLogService activityLogService;
    @Mock private BadgeService badgeService;
    @Mock private XpService xpService;

    @InjectMocks
    private ChatController chatController;

    @Test
    void getChatHistory_avatarNotFound_throwsAvatarNotFoundException() {
        when(avatarRepository.findById("nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                chatController.getChatHistory("user-1", "nonexistent"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getChatHistory_avatarBelongsToOtherUser_throwsAvatarNotFoundException() {
        // Create an avatar that belongs to a different user
        var avatar = com.pally.domain.avatar.Avatar.create(
                "other-user", "Test", com.pally.domain.avatar.Subject.MATHS,
                com.pally.domain.avatar.CharacterType.MOCHI);

        when(avatarRepository.findById(avatar.getId()))
                .thenReturn(Optional.of(avatar));

        // Requesting with a different userId should throw
        assertThatThrownBy(() ->
                chatController.getChatHistory("user-1", avatar.getId()))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getChatHistory_validRequest_returnsMessages() {
        var avatar = com.pally.domain.avatar.Avatar.create(
                "user-1", "MathBot", com.pally.domain.avatar.Subject.MATHS,
                com.pally.domain.avatar.CharacterType.MOCHI);

        when(avatarRepository.findById(avatar.getId()))
                .thenReturn(Optional.of(avatar));
        when(chatRepository.findByAvatarId(eq(avatar.getId()), anyInt()))
                .thenReturn(List.of());
        when(chatMapper.toResponseList(anyList()))
                .thenReturn(List.of());

        var result = chatController.getChatHistory("user-1", avatar.getId());
        assertThat(result).isEmpty();
    }
}
