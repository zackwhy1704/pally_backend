package com.pally.domain.chat.usecase;

import com.pally.domain.chat.ChatRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatFeedbackService {

    private static final Set<String> VALID_TYPES =
            Set.of("HELPFUL", "WRONG", "CONFUSED", "SAVE_TO_BRAIN");

    private final ChatRepository chatRepo;

    @Transactional
    public void submitFeedback(String messageId, String feedbackType, String userId) {
        String upper = feedbackType != null ? feedbackType.toUpperCase() : "";
        if (!VALID_TYPES.contains(upper)) {
            throw new BusinessException("Invalid feedback type: " + feedbackType, 400);
        }

        // IDOR guard: only the message's owner may rate/save it. 404 (not 403) so
        // another user's message existence isn't revealed.
        if (!chatRepo.existsByIdAndUserId(messageId, userId)) {
            throw new BusinessException("Message not found: " + messageId, 404);
        }

        if ("SAVE_TO_BRAIN".equals(upper)) {
            chatRepo.markSavedToBrain(messageId);
            chatRepo.updateFeedbackType(messageId, upper);
        } else {
            chatRepo.updateFeedbackType(messageId, upper);
        }

        log.info("[ChatFeedback] message={} type={}", messageId, upper);
    }
}
