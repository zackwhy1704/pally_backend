package com.pally.infrastructure.persistence.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageJpaRepository extends JpaRepository<ChatMessageJpaEntity, String> {

    List<ChatMessageJpaEntity> findByAvatarIdOrderByCreatedAtDesc(String avatarId, Pageable pageable);
}
