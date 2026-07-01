package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarKind;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvatarUseCaseListFilterTest {

    @Mock private AvatarRepository avatarRepository;
    @InjectMocks private GetAvatarUseCase useCase;

    private Avatar personal() {
        return Avatar.create("u1", "My Mochi", Subject.MATHS, CharacterType.MOCHI);
    }

    private Avatar centreClass() {
        Avatar a = Avatar.create("u1", "Class Corpus", Subject.MATHS, CharacterType.MOCHI);
        a.markCentreClassAvatar();
        return a;
    }

    private Avatar markingCorpus() {
        Avatar a = Avatar.create("u1", "Maths Marking Standard", Subject.MATHS, CharacterType.MOCHI);
        a.markMarkingCorpus();
        return a;
    }

    @Test
    void getAllForUser_hidesMarkingCorpus_keepsPersonalAndCentreClass() {
        Avatar personal = personal();
        Avatar centre = centreClass();
        when(avatarRepository.findByUserId("u1"))
                .thenReturn(List.of(personal, centre, markingCorpus()));

        List<Avatar> result = useCase.getAllForUser("u1");

        assertThat(result).containsExactly(personal, centre);
        assertThat(result).noneMatch(a -> a.getKind() == AvatarKind.MARKING_CORPUS);
    }
}
