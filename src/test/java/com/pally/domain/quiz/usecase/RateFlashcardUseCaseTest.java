package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateFlashcardUseCaseTest {

    @Mock FlashcardRepository flashcardRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock com.pally.domain.learning.LearningEventRepository learningEventRepository;

    private static final String CARD_ID   = "card-1";
    private static final String AVATAR_ID = "avatar-1";
    private static final String OWNER     = "user-1";
    private static final String ATTACKER  = "user-2";

    private RateFlashcardUseCase useCase() {
        return new RateFlashcardUseCase(flashcardRepository, avatarRepository, learningEventRepository);
    }

    private FlashCard card() {
        return new FlashCard(CARD_ID, AVATAR_ID, "front", "back", "slug",
                CardRating.OKAY, Instant.now(), 1, 2.5, 1);
    }

    @Test
    void execute_cardOwnedByUser_appliesRatingAndSaves() {
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(avatarRepository.existsByIdAndUserId(AVATAR_ID, OWNER)).thenReturn(true);
        when(flashcardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FlashCard result = useCase().execute(CARD_ID, CardRating.EASY, OWNER);

        assertThat(result).isNotNull();
        verify(flashcardRepository).save(any());
    }

    @Test
    void execute_writesLearningEvent_spacedVerifiedRecall() {
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(avatarRepository.existsByIdAndUserId(AVATAR_ID, OWNER)).thenReturn(true);
        when(flashcardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        useCase().execute(CARD_ID, CardRating.EASY, OWNER);

        org.mockito.ArgumentCaptor<com.pally.domain.learning.LearningEvent> cap =
                org.mockito.ArgumentCaptor.forClass(com.pally.domain.learning.LearningEvent.class);
        verify(learningEventRepository).save(cap.capture());
        com.pally.domain.learning.LearningEvent event = cap.getValue();
        assertThat(event.source()).isEqualTo(com.pally.domain.learning.LearningEventSource.FLASHCARD);
        assertThat(event.provenance())
                .isEqualTo(com.pally.domain.learning.LearningEventProvenance.SPACED_VERIFIED_RECALL);
        assertThat(event.userId()).isEqualTo(OWNER);
        assertThat(event.avatarId()).isEqualTo(AVATAR_ID);
        assertThat(event.sourceRowId()).isEqualTo(CARD_ID);
        assertThat(event.score()).isEqualByComparingTo(java.math.BigDecimal.ONE); // EASY -> 1.0
    }

    @Test
    void execute_cardNotOwnedByCaller_throws404_andNeverSaves() {
        // IDOR: a different user must not be able to rate (and corrupt the SM-2
        // schedule of) someone else's flashcard.
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.of(card()));
        when(avatarRepository.existsByIdAndUserId(AVATAR_ID, ATTACKER)).thenReturn(false);

        assertThatThrownBy(() -> useCase().execute(CARD_ID, CardRating.EASY, ATTACKER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);

        verify(flashcardRepository, never()).save(any());
    }

    @Test
    void execute_cardNotFound_throws404() {
        when(flashcardRepository.findById(CARD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase().execute(CARD_ID, CardRating.EASY, OWNER))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }
}
