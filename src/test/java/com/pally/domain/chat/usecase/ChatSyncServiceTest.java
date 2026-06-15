package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.SyncMessageDto;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSyncServiceTest {

    @Mock
    private ChatRepository chatRepo;

    private ChatSyncService service;

    private static final String AVATAR_ID = "avatar-1";
    private static final String USER_ID = "user-1";
    private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new ChatSyncService(chatRepo);
    }

    @Test
    void sync_newMessage_savesViaChatRepository() {
        SyncMessageDto dto = new SyncMessageDto(
                "msg-new", ChatMessage.Role.USER, "content", "text",
                null, null, false, false, NOW);
        when(chatRepo.existsById("msg-new")).thenReturn(false);
        when(chatRepo.findByAvatarIdSince(eq(AVATAR_ID), any())).thenReturn(List.of());

        int result = service.sync(AVATAR_ID, USER_ID, List.of(dto));

        assertThat(result).isEqualTo(1);
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatRepo).save(captor.capture());
        ChatMessage saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("msg-new");
        assertThat(saved.getAvatarId()).isEqualTo(AVATAR_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(saved.getContent()).isEqualTo("content");
    }

    @Test
    void sync_existingMessage_updatesOnlyMutableFields() {
        SyncMessageDto dto = new SyncMessageDto(
                "msg-existing", ChatMessage.Role.USER, "content", "text",
                null, "HELPFUL", true, false, NOW);
        when(chatRepo.existsById("msg-existing")).thenReturn(true);

        int result = service.sync(AVATAR_ID, USER_ID, List.of(dto));

        assertThat(result).isEqualTo(0);
        verify(chatRepo).updateFeedbackType("msg-existing", "HELPFUL");
        verify(chatRepo).markSavedToBrain("msg-existing");
        verify(chatRepo, never()).save(any());
    }

    @Test
    void sync_duplicateContentWithinWindow_skipsMessage() {
        Instant createdAt = NOW;
        SyncMessageDto dto = new SyncMessageDto(
                "msg-dup", ChatMessage.Role.USER, "same content", "text",
                null, null, false, false, createdAt);
        when(chatRepo.existsById("msg-dup")).thenReturn(false);

        ChatMessage existing = ChatMessage.reconstitute(
                "msg-old", AVATAR_ID, USER_ID,
                ChatMessage.Role.USER, "same content", null,
                "text", null, null, false, false,
                createdAt.minusSeconds(10), null);
        when(chatRepo.findByAvatarIdSince(eq(AVATAR_ID), any())).thenReturn(List.of(existing));

        int result = service.sync(AVATAR_ID, USER_ID, List.of(dto));

        assertThat(result).isEqualTo(0);
        verify(chatRepo, never()).save(any());
    }

    @Test
    void sync_noFeedbackType_doesNotCallUpdateFeedback() {
        SyncMessageDto dto = new SyncMessageDto(
                "msg-existing", ChatMessage.Role.USER, "content", "text",
                null, null, false, false, NOW);
        when(chatRepo.existsById("msg-existing")).thenReturn(true);

        service.sync(AVATAR_ID, USER_ID, List.of(dto));

        verify(chatRepo, never()).updateFeedbackType(any(), any());
        verify(chatRepo, never()).markSavedToBrain(any());
    }

    @Test
    void sync_nullMessageType_defaultsToText() {
        SyncMessageDto dto = new SyncMessageDto(
                "msg-notype", ChatMessage.Role.USER, "hello", null,
                null, null, false, false, NOW);
        when(chatRepo.existsById("msg-notype")).thenReturn(false);
        when(chatRepo.findByAvatarIdSince(eq(AVATAR_ID), any())).thenReturn(List.of());

        service.sync(AVATAR_ID, USER_ID, List.of(dto));

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatRepo).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo("text");
    }
}
