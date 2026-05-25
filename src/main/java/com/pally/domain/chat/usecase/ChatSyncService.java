package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.SyncMessageDto;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaEntity;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSyncService {

    private final ChatMessageJpaRepository repo;

    @Transactional
    public int sync(String avatarId, String userId, List<SyncMessageDto> messages) {
        int upserted = 0;
        for (SyncMessageDto dto : messages) {
            if (repo.existsById(dto.id())) {
                // Update mutable fields only (feedback, savedToBrain can change)
                if (dto.feedbackType() != null) {
                    repo.updateFeedbackType(dto.id(), dto.feedbackType());
                }
                if (dto.savedToBrain()) {
                    repo.markSavedToBrain(dto.id());
                }
            } else {
                ChatMessageJpaEntity e = new ChatMessageJpaEntity();
                e.setId(dto.id());
                e.setAvatarId(avatarId);
                e.setUserId(userId);
                e.setRole(dto.role());
                e.setContent(dto.content());
                e.setMessageType(dto.messageType() != null ? dto.messageType() : "text");
                e.setSourceWikiSlug(dto.sourceWikiSlug());
                e.setFeedbackType(dto.feedbackType());
                e.setSavedToBrain(dto.savedToBrain());
                e.setPhotoMessage(dto.isPhotoMessage());
                e.setCreatedAt(dto.createdAt());
                repo.save(e);
                upserted++;
            }
        }
        log.info("[ChatSync] avatar={} synced={} total={}", avatarId, upserted, messages.size());
        return upserted;
    }
}
