package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Use case: create a new avatar for a user.
 */
@Service
@RequiredArgsConstructor
public class CreateAvatarUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateAvatarUseCase.class);

    private final AvatarRepository avatarRepository;

    public Avatar execute(String userId, String name, Subject subject, CharacterType characterType) {
        return execute(userId, name, subject, characterType, null, null);
    }

    public Avatar execute(String userId, String name, Subject subject, CharacterType characterType,
                          String gradeLevel, String curriculumType) {
        log.info("Creating avatar for userId={} name={} subject={} grade={}", userId, name, subject, gradeLevel);
        Avatar avatar = Avatar.create(userId, name, subject, characterType, gradeLevel, curriculumType);
        Avatar saved = avatarRepository.save(avatar);
        log.info("Avatar created id={}", saved.getId());
        return saved;
    }
}
