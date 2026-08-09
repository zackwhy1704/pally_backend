package com.pally.domain.chat;

import com.pally.domain.chat.ChatMapper;
import com.pally.domain.chat.dto.ChatHistoryResponse;
import com.pally.domain.chat.dto.ChatMessageResponse;
import com.pally.domain.chat.dto.SyncMessageDto;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.chat.port.ChatSessionCachePort;
import com.pally.domain.chat.usecase.ChatFeedbackService;
import com.pally.domain.chat.usecase.ChatHistoryService;
import com.pally.domain.chat.usecase.ChatSyncService;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.XpService;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Orchestrates all non-streaming chat operations and post-session side-effects.
 *
 * <p>This service exists solely to thin {@code ChatController}: every handler that
 * contained business logic, direct repository calls, or XP/badge side-effects has
 * been moved here. The controller is left with ≤5 dependencies and zero business
 * logic — each handler validates input, delegates here, and wraps the result.
 *
 * <p>No imports from {@code com.pally.infrastructure.*} or {@code jakarta.persistence.*}
 * — cache keepalive is reached through {@link ChatSessionCachePort}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatOrchestrationService {

    private static final int HISTORY_PAGE_SIZE = 50;

    private final AvatarRepository avatarRepository;
    private final ChatRepository chatRepository;
    private final ChatMapper chatMapper;
    private final ChatSyncService chatSyncService;
    private final ChatHistoryService chatHistoryService;
    private final ChatFeedbackService chatFeedbackService;
    private final ChatSessionCachePort chatSessionCachePort;
    private final XpService xpService;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;

    /**
     * Returns the recent chat history for an avatar, asserting ownership first.
     *
     * @throws AvatarNotFoundException if the avatar does not exist or belongs to another user
     */
    public List<ChatMessageResponse> getChatHistory(String userId, String avatarId) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        List<ChatMessage> messages = chatRepository.findByAvatarId(avatarId, HISTORY_PAGE_SIZE);
        return chatMapper.toResponseList(messages);
    }

    /**
     * Returns the full paginated chat history, asserting ownership first.
     *
     * @throws AvatarNotFoundException if the avatar does not exist or belongs to another user
     */
    public ChatHistoryResponse getFullHistory(String userId, String avatarId, int limit) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        return new ChatHistoryResponse(chatHistoryService.getHistory(avatarId, limit));
    }

    /**
     * Syncs offline messages to the server and returns the upserted count, asserting
     * ownership of the target avatar first.
     *
     * @throws AvatarNotFoundException if the avatar does not exist or belongs to another user
     */
    public Map<String, Integer> syncMessages(String userId, String avatarId,
                                             List<SyncMessageDto> messages) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        int upserted = chatSyncService.sync(avatarId, userId, messages);
        return Map.of("upserted", upserted);
    }

    /** Records user feedback on a specific message. */
    public void submitFeedback(String messageId, String feedbackType, String userId) {
        chatFeedbackService.submitFeedback(messageId, feedbackType, userId);
    }

    /** Starts the AI-cache keepalive when the user opens the chat screen. */
    public void sessionStart(String avatarId) {
        chatSessionCachePort.startKeepalive(avatarId);
    }

    /**
     * Ends a chat session: stops keepalive and awards once-per-SGT-day XP.
     *
     * <p>XP/badge/activity-log side-effects are swallowed on failure so a
     * transient DB error never surfaces to the user as a session-end failure.
     *
     * @return map with {@code levelledUp}, {@code newLevel}, {@code rewardLabel},
     *         {@code alreadyCreditedToday}
     */
    public Map<String, Object> sessionEnd(String userId, String avatarId) {
        chatSessionCachePort.stopKeepalive(avatarId);

        boolean levelledUp = false;
        int newLevel = 0;
        String rewardLabel = null;
        boolean alreadyCredited = false;
        try {
            var award = xpService.awardForChat(userId, avatarId);
            levelledUp = award.creditResult().levelledUp();
            newLevel = award.creditResult().newLevel();
            rewardLabel = award.creditResult().unlockedRewardLabel();
            alreadyCredited = award.alreadyCreditedToday();
            if (!alreadyCredited) {
                activityLogService.log(userId, avatarId,
                        ActivityLogService.TYPE_CHAT,
                        0, award.xpGranted());
                badgeService.grantFirstAction(userId, BadgeService.BadgeType.FIRST_CHAT);
                badgeService.checkAndGrantMilestones(userId);
            }
        } catch (Exception ignored) {
            log.warn("[ChatOrchestration] session-end side-effects failed for user={} avatar={}",
                    userId, avatarId);
        }
        // HashMap, not Map.of — rewardLabel is null on most calls (no level
        // crossed, or a crossing with no reward tier), and Map.of forbids null.
        Map<String, Object> result = new HashMap<>();
        result.put("levelledUp", levelledUp);
        result.put("newLevel", newLevel);
        result.put("rewardLabel", rewardLabel);
        result.put("alreadyCreditedToday", alreadyCredited);
        return result;
    }

    /**
     * Updates the teaching mode for an avatar, asserting ownership first.
     *
     * @throws AvatarNotFoundException if the avatar does not exist or belongs to another user
     */
    public Map<String, String> setTeachingMode(String userId, String avatarId, TeachingMode mode) {
        var avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        avatar.setTeachingMode(mode);
        avatarRepository.save(avatar);
        return Map.of("mode", mode.name());
    }
}
