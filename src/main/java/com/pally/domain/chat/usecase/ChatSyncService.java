package com.pally.domain.chat.usecase;

import com.pally.domain.chat.dto.SyncMessageDto;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSyncService {

    private final ChatRepository chatRepo;

    @Transactional
    public int sync(String avatarId, String userId, List<SyncMessageDto> messages) {
        int upserted = 0;
        for (SyncMessageDto dto : messages) {
            if (chatRepo.existsById(dto.id())) {
                // Update mutable fields only (feedback, savedToBrain can change)
                if (dto.feedbackType() != null) {
                    chatRepo.updateFeedbackType(dto.id(), dto.feedbackType());
                }
                if (dto.savedToBrain()) {
                    chatRepo.markSavedToBrain(dto.id());
                }
            } else {
                boolean isDuplicate = chatRepo
                        .findByAvatarIdSince(avatarId, dto.createdAt().minusSeconds(30))
                        .stream()
                        .anyMatch(existing ->
                                existing.getRole() != null &&
                                existing.getRole().name().equalsIgnoreCase(dto.role().name()) &&
                                existing.getContent() != null &&
                                existing.getContent().equals(dto.content()));

                if (isDuplicate) {
                    String preview = dto.content() == null
                            ? ""
                            : dto.content().substring(0, Math.min(40, dto.content().length()));
                    log.debug("[ChatSync] Skipping duplicate: role={} content='{}'",
                            dto.role(), preview);
                    continue;
                }

                ChatMessage msg = ChatMessage.reconstitute(
                        dto.id(), avatarId, userId,
                        dto.role(), dto.content(), null,
                        dto.messageType() != null ? dto.messageType() : "text",
                        dto.sourceWikiSlug(),
                        dto.feedbackType(), dto.savedToBrain(), dto.isPhotoMessage(),
                        dto.createdAt(), null);
                chatRepo.save(msg);
                upserted++;
            }
        }
        log.info("[ChatSync] avatar={} synced={} total={}", avatarId, upserted, messages.size());
        return upserted;
    }
}
