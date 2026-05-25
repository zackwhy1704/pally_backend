package com.pally.infrastructure.persistence.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ChatSessionJpaRepository extends JpaRepository<ChatSessionJpaEntity, String> {
    Optional<ChatSessionJpaEntity> findByAvatarIdAndSessionDate(String avatarId, LocalDate sessionDate);
}
