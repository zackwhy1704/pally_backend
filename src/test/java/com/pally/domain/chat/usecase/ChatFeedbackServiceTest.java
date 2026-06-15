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

    @Mock
    private ChatRepository chatRepo;

    private ChatFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new ChatFeedbackService(chatRepo);
    }

    @Test
    void submitFeedback_invalidType_throwsBusinessException() {
        assertThatThrownBy(() -> service.submitFeedback("msg-1", "UNKNOWN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid feedback type");
        verifyNoInteractions(chatRepo);
    }

    @Test
    void submitFeedback_messageNotFound_throwsBusinessException() {
        when(chatRepo.existsById("msg-1")).thenReturn(false);

        assertThatThrownBy(() -> service.submitFeedback("msg-1", "HELPFUL"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Message not found");
    }

    @Test
    void submitFeedback_helpfulType_updatesOnlyFeedbackType() {
        when(chatRepo.existsById("msg-1")).thenReturn(true);

        service.submitFeedback("msg-1", "helpful");

        verify(chatRepo).updateFeedbackType("msg-1", "HELPFUL");
        verify(chatRepo, never()).markSavedToBrain(any());
    }

    @Test
    void submitFeedback_saveToBrain_marksSavedAndUpdatesFeedbackType() {
        when(chatRepo.existsById("msg-2")).thenReturn(true);

        service.submitFeedback("msg-2", "SAVE_TO_BRAIN");

        verify(chatRepo).markSavedToBrain("msg-2");
        verify(chatRepo).updateFeedbackType("msg-2", "SAVE_TO_BRAIN");
    }

    @Test
    void submitFeedback_nullFeedbackType_throwsBusinessException() {
        assertThatThrownBy(() -> service.submitFeedback("msg-1", null))
                .isInstanceOf(BusinessException.class);
    }
}
