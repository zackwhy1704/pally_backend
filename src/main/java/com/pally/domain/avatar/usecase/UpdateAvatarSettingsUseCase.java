package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class UpdateAvatarSettingsUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateAvatarSettingsUseCase.class);

    private final AvatarRepository avatarRepository;
    private final GetAvatarUseCase getAvatarUseCase;

    /**
     * Class avatars are centre-managed: students cannot rename or change their
     * settings. The centre's class config (name, subject, branding) is the only
     * source of truth, propagated via ClassController. Reject student edits 403.
     */
    private Avatar requireStudentEditable(String avatarId, String userId) {
        Avatar avatar = getAvatarUseCase.getById(avatarId, userId);
        if (avatar.isCentreClass()) {
            throw new BusinessException("Class avatars cannot be edited", 403);
        }
        return avatar;
    }

    public Avatar updateGradeCurriculum(String avatarId, String userId,
                                        String gradeLevel, String curriculumType) {
        Avatar avatar = requireStudentEditable(avatarId, userId);
        avatar.setGradeLevel(gradeLevel);
        avatar.setCurriculumType(curriculumType);
        Avatar saved = avatarRepository.save(avatar);
        log.info("Updated grade/curriculum avatarId={} grade={} curriculum={}", avatarId, gradeLevel, curriculumType);
        return saved;
    }

    public Avatar updateTestDate(String avatarId, String userId, LocalDate testDate) {
        Avatar avatar = requireStudentEditable(avatarId, userId);
        avatar.setTestDate(testDate);
        Avatar saved = avatarRepository.save(avatar);
        log.info("Updated testDate avatarId={} testDate={}", avatarId, testDate);
        return saved;
    }

    /**
     * Updates the optional teacher-specified method preferences.
     * Null or blank input clears the field; over-limit input is rejected upstream.
     */
    public Avatar updateTeacherPreferences(String avatarId, String userId, String teacherPreferences) {
        Avatar avatar = requireStudentEditable(avatarId, userId);
        avatar.setTeacherPreferences(teacherPreferences == null || teacherPreferences.isBlank()
                ? null
                : teacherPreferences.strip());
        Avatar saved = avatarRepository.save(avatar);
        log.info("Updated teacherPreferences avatarId={} hasPrefs={}", avatarId,
                saved.getTeacherPreferences() != null);
        return saved;
    }
}
