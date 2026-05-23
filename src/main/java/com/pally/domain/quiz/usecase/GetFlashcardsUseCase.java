package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetFlashcardsUseCase {

    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;

    public List<FlashCard> execute(String avatarId, String userId) {
        if (!avatarRepository.existsByIdAndUserId(avatarId, userId)) {
            throw new AvatarNotFoundException(avatarId);
        }
        return flashcardRepository.findByAvatarId(avatarId);
    }
}
