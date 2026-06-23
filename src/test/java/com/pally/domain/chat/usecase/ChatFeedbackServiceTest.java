package com.pally.domain.chat.usecase;

import com.pally.domain.chat.ChatRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatFeedbackServiceTest {

    private static final String USER = "user-1";

    @Mock
    private ChatRepository chatRepo;

    private ChatFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new ChatFeedbackService(chatRepo);
    }

    @Test
    void submitFeedback_invalidType_throwsBusinessException() {
        assertThatThrownBy(() -> service.submitFeedback("msg-1", "UNKNOWN", USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid feedback type");
        verifyNoInteractions(chatRepo);
    }

    @Test
    void submitFeedback_messageNotOwnedByCaller_throwsAndNeverWrites() {
        // IDOR: a message that doesn't belong to the caller must be rejected, and
        // no feedback/SAVE_TO_BRAIN mutation may happen.
        when(chatRepo.existsByIdAndUserId("msg-1", USER)).thenReturn(false);

        assertThatThrownBy(() -> service.submitFeedback("msg-1", "HELPFUL", USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Message not found");

        verify(chatRepo, never()).updateFeedbackType(any(), any());
        verify(chatRepo, never()).markSavedToBrain(any());
    }

    @Test
    void submitFeedback_helpfulType_updatesOnlyFeedbackType() {
        when(chatRepo.existsByIdAndUserId("msg-1", USER)).thenReturn(true);

        service.submitFeedback("msg-1", "helpful", USER);

        verify(chatRepo).updateFeedbackType("msg-1", "HELPFUL");
        verify(chatRepo, never()).markSavedToBrain(any());
    }

    @Test
    void submitFeedback_saveToBrain_marksSavedAndUpdatesFeedbackType() {
        when(chatRepo.existsByIdAndUserId("msg-2", USER)).thenReturn(true);

        service.submitFeedback("msg-2", "SAVE_TO_BRAIN", USER);

        verify(chatRepo).markSavedToBrain("msg-2");
        verify(chatRepo).updateFeedbackType("msg-2", "SAVE_TO_BRAIN");
    }

    @Test
    void submitFeedback_nullFeedbackType_throwsBusinessException() {
        assertThatThrownBy(() -> service.submitFeedback("msg-1", null, USER))
                .isInstanceOf(BusinessException.class);
    }
}
