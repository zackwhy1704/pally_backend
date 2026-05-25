package com.pally.domain.chat.usecase;

import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
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

    private final ChatMessageJpaRepository repo;

    @Transactional
    public void submitFeedback(String messageId, String feedbackType) {
        String upper = feedbackType != null ? feedbackType.toUpperCase() : "";
        if (!VALID_TYPES.contains(upper)) {
            throw new BusinessException("Invalid feedback type: " + feedbackType, 400);
        }

        if (!repo.existsById(messageId)) {
            throw new BusinessException("Message not found: " + messageId, 404);
        }

        if ("SAVE_TO_BRAIN".equals(upper)) {
            repo.markSavedToBrain(messageId);
            repo.updateFeedbackType(messageId, upper);
        } else {
            repo.updateFeedbackType(messageId, upper);
        }

        log.info("[ChatFeedback] message={} type={}", messageId, upper);
    }
}
