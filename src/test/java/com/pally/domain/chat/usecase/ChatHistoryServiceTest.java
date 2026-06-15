package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.SyncMessageDto;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceTest {

    @Mock
    private ChatRepository chatRepo;

    private ChatHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryService(chatRepo);
    }

    @Test
    void getHistory_emptyAvatar_returnsEmptyList() {
        when(chatRepo.findByAvatarId("avatar-1", 50)).thenReturn(List.of());

        List<SyncMessageDto> result = service.getHistory("avatar-1", 50);

        assertThat(result).isEmpty();
    }

    @Test
    void getHistory_mapsAllFieldsFromDomainToDto() {
        Instant ts = Instant.parse("2024-01-01T10:00:00Z");
        ChatMessage msg = ChatMessage.reconstitute(
                "msg-1", "avatar-1", "user-1",
                ChatMessage.Role.USER, "Hello", null,
                "text", "slug-1",
                "HELPFUL", true, false,
                ts, null);
        when(chatRepo.findByAvatarId("avatar-1", 10)).thenReturn(List.of(msg));

        List<SyncMessageDto> result = service.getHistory("avatar-1", 10);

        assertThat(result).hasSize(1);
        SyncMessageDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo("msg-1");
        assertThat(dto.role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(dto.content()).isEqualTo("Hello");
        assertThat(dto.messageType()).isEqualTo("text");
        assertThat(dto.sourceWikiSlug()).isEqualTo("slug-1");
        assertThat(dto.feedbackType()).isEqualTo("HELPFUL");
        assertThat(dto.savedToBrain()).isTrue();
        assertThat(dto.isPhotoMessage()).isFalse();
        assertThat(dto.createdAt()).isEqualTo(ts);
    }

    @Test
    void getHistory_passesLimitThroughToRepository() {
        when(chatRepo.findByAvatarId("avatar-1", 100)).thenReturn(List.of());

        service.getHistory("avatar-1", 100);

        verify(chatRepo).findByAvatarId("avatar-1", 100);
    }
}
