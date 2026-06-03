package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
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

    public Avatar updateGradeCurriculum(String avatarId, String userId,
                                        String gradeLevel, String curriculumType) {
        Avatar avatar = getAvatarUseCase.getById(avatarId, userId);
        avatar.setGradeLevel(gradeLevel);
        avatar.setCurriculumType(curriculumType);
        Avatar saved = avatarRepository.save(avatar);
        log.info("Updated grade/curriculum avatarId={} grade={} curriculum={}", avatarId, gradeLevel, curriculumType);
        return saved;
    }

    public Avatar updateTestDate(String avatarId, String userId, LocalDate testDate) {
        Avatar avatar = getAvatarUseCase.getById(avatarId, userId);
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
        Avatar avatar = getAvatarUseCase.getById(avatarId, userId);
        avatar.setTeacherPreferences(teacherPreferences == null || teacherPreferences.isBlank()
                ? null
                : teacherPreferences.strip());
        Avatar saved = avatarRepository.save(avatar);
        log.info("Updated teacherPreferences avatarId={} hasPrefs={}", avatarId,
                saved.getTeacherPreferences() != null);
        return saved;
    }
}
