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

    private Avatar syllabusPack() {
        Avatar a = Avatar.create("u1", "SG-G3-COMPUTING-7155 / Algorithms", Subject.CODING, CharacterType.MOCHI);
        a.markSyllabusPack();
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

    @Test
    void getAllForUser_hidesSyllabusPack_soUploadYourOwnAndStarterContentNeverCollideInAStudentsList() {
        // Real-world path this pins: PLATFORM_SYSTEM_USER_ID never equals a real
        // student's userId, so findByUserId would never actually return one of these in
        // practice — but the family filter (mirroring MARKING_CORPUS/WEAKNESS_PROFILE)
        // is defense-in-depth against a future owner-id bug leaking a hidden pack avatar
        // into a real user's upload-your-own avatar list.
        Avatar personal = personal();
        when(avatarRepository.findByUserId("u1"))
                .thenReturn(List.of(personal, syllabusPack()));

        List<Avatar> result = useCase.getAllForUser("u1");

        assertThat(result).containsExactly(personal);
        assertThat(result).noneMatch(a -> a.getKind() == AvatarKind.SYLLABUS_PACK);
    }
}
