package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckRelevanceUseCaseTest {

    @Mock AvatarRepository avatarRepository;
    @Mock WikiRepository wikiRepository;
    @Mock RelevancePort relevancePort;

    @InjectMocks CheckRelevanceUseCase useCase;

    private static final String AVATAR_ID = "avatar-001";
    private static final String USER_ID = "user-001";

    private Avatar makeAvatar() {
        return Avatar.create(USER_ID, "Robo", Subject.MATHS, CharacterType.BYTE);
    }

    @Test
    void execute_withRelevantContent_returnsHighScore() {
        Avatar avatar = makeAvatar();
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(relevancePort.check(anyString(), anyString(), anyString()))
                .thenReturn(new RelevanceScore(0.87, "Content is about algebra — highly relevant to Maths"));

        CheckRelevanceUseCase.RelevanceResult result = useCase.execute(AVATAR_ID, "quadratic equations sample text");

        assertThat(result.relevant()).isTrue();
        assertThat(result.score()).isEqualTo(0.87);
        assertThat(result.reason()).contains("algebra");
    }

    @Test
    void execute_withOffTopicContent_returnsLowScore() {
        Avatar avatar = makeAvatar();
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(relevancePort.check(anyString(), anyString(), anyString()))
                .thenReturn(new RelevanceScore(0.12, "Content is about world history, not maths"));

        CheckRelevanceUseCase.RelevanceResult result = useCase.execute(AVATAR_ID, "world war II timeline 1939 Germany");

        assertThat(result.relevant()).isFalse();
        assertThat(result.score()).isLessThan(0.45);
    }

    @Test
    void execute_onBoundaryScore_treatsAsIrrelevant() {
        Avatar avatar = makeAvatar();
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(relevancePort.check(any(), any(), any()))
                .thenReturn(new RelevanceScore(0.44, "Borderline case"));

        assertThat(useCase.execute(AVATAR_ID, "some text").relevant()).isFalse();
    }

    @Test
    void execute_onThresholdScore_treatsAsRelevant() {
        Avatar avatar = makeAvatar();
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(relevancePort.check(any(), any(), any()))
                .thenReturn(new RelevanceScore(0.45, "At threshold"));

        assertThat(useCase.execute(AVATAR_ID, "some text").relevant()).isTrue();
    }

    @Test
    void execute_withUnknownAvatar_throwsNotFoundException() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(AVATAR_ID, "any content"))
                .isInstanceOf(AvatarNotFoundException.class);
    }
}
