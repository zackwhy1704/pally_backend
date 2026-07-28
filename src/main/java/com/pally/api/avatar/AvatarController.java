package com.pally.api.avatar;

import com.pally.api.avatar.dto.AvatarListResponse;
import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.api.avatar.dto.CreateAvatarRequest;
import com.pally.api.avatar.dto.UpdateGradeCurriculumRequest;
import com.pally.api.avatar.dto.UpdateTestDateRequest;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.usecase.CreateAvatarUseCase;
import com.pally.domain.avatar.usecase.DeleteAvatarUseCase;
import com.pally.domain.avatar.usecase.GetAvatarUseCase;
import com.pally.domain.avatar.usecase.SetAvatarActiveUseCase;
import com.pally.domain.avatar.usecase.UpdateAvatarSettingsUseCase;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import com.pally.shared.exception.BusinessException;

/**
 * REST controller for avatar management endpoints.
 *
 * <p>All endpoints require a {@code X-User-Id} header identifying the calling user.
 */
@RestController
@RequestMapping("/api/v1/avatars")
@RequiredArgsConstructor
public class AvatarController {

    private final CreateAvatarUseCase createAvatarUseCase;
    private final GetAvatarUseCase getAvatarUseCase;
    private final DeleteAvatarUseCase deleteAvatarUseCase;
    private final UpdateAvatarSettingsUseCase updateAvatarSettingsUseCase;
    private final SetAvatarActiveUseCase setAvatarActiveUseCase;
    private final AvatarMapper avatarMapper;

    /**
     * Creates a new avatar for the authenticated user.
     *
     * @param userId  user identifier from {@code X-User-Id} header
     * @param request creation parameters
     * @return 201 Created with the new avatar's details
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AvatarResponse>> createAvatar(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CreateAvatarRequest request
    ) {
        Avatar avatar = createAvatarUseCase.execute(
                userId, request.name(), request.subject(), request.characterType(),
                request.gradeLevel(), request.curriculumType(), request.contentLanguage()
        );
        AvatarResponse response = avatarMapper.toResponse(avatar);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created(response));
    }

    /**
     * Lists all avatars belonging to the authenticated user.
     *
     * @param userId user identifier from {@code X-User-Id} header
     * @return 200 OK with list of avatars
     */
    @GetMapping
    public ResponseEntity<ApiResponse<AvatarListResponse>> listAvatars(
            @AuthenticationPrincipal String userId
    ) {
        List<Avatar> avatars = getAvatarUseCase.getAllForUser(userId);
        AvatarListResponse response = new AvatarListResponse(avatarMapper.toResponseList(avatars));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Retrieves a single avatar by ID.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @return 200 OK with avatar details, or 404 if not found / not owned by user
     */
    @GetMapping("/{avatarId}")
    public ResponseEntity<ApiResponse<AvatarResponse>> getAvatar(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        Avatar avatar = getAvatarUseCase.getById(avatarId, userId);
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }

    /**
     * Deletes an avatar and all associated data.
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @return 204 No Content on success
     */
    @DeleteMapping("/{avatarId}")
    public ResponseEntity<Void> deleteAvatar(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        deleteAvatarUseCase.execute(avatarId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Activates or deactivates an avatar slot.
     *
     * <p>Free users must stay within {@code LevelRewards.freeTutorCap} active avatars
     * and may only activate one Mochi per 24-hour window. Premium users are unlimited.
     *
     * @param avatarId  avatar to change
     * @param activate  {@code true} = activate, {@code false} = deactivate
     * @return 200 OK with updated active state; 422 if cap exceeded; 429 if in cooldown
     */
    @PatchMapping("/{avatarId}/active")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> setActive(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody java.util.Map<String, Boolean> body
    ) {
        boolean activate = Boolean.TRUE.equals(body.get("active"));
        SetAvatarActiveUseCase.Result result = setAvatarActiveUseCase.execute(avatarId, userId, activate);
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                "isActive", result.isActive(),
                "message", result.message(),
                "cooldownSecondsRemaining", result.cooldownSecondsRemaining()
        )));
    }

    @PatchMapping("/{avatarId}/grade")
    public ResponseEntity<ApiResponse<AvatarResponse>> updateGradeCurriculum(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody UpdateGradeCurriculumRequest request
    ) {
        Avatar avatar = updateAvatarSettingsUseCase.updateGradeCurriculum(
                avatarId, userId, request.gradeLevel(), request.curriculumType());
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }

    @PatchMapping("/{avatarId}/test-date")
    public ResponseEntity<ApiResponse<AvatarResponse>> updateTestDate(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody UpdateTestDateRequest request
    ) {
        Avatar avatar = updateAvatarSettingsUseCase.updateTestDate(avatarId, userId, request.testDate());
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }

    /**
     * Sets the language the avatar generates content in ('en' | 'zh') — V124. An unsupported value
     * returns 400 (never a silent default). NOTE: changing this does NOT retag existing artifacts —
     * pages/modules/flashcards keep their compile-time language, so the avatar generates NEW material
     * in the new language while old material stays as it was. Recompile a page to regenerate it.
     *
     * @return 200 OK with the updated avatar; 400 if the language is unsupported
     */
    @PatchMapping("/{avatarId}/content-language")
    public ResponseEntity<ApiResponse<AvatarResponse>> updateContentLanguage(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody Map<String, String> body
    ) {
        String lang = com.pally.domain.i18n.SupportedLanguage.validate(body.get("contentLanguage"));
        Avatar avatar = updateAvatarSettingsUseCase.updateContentLanguage(avatarId, userId, lang);
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }

    /**
     * Sets or clears teacher-specified method preferences for an avatar.
     * Max 500 characters. Send blank/null to clear.
     *
     * @param userId   authenticated user
     * @param avatarId target avatar
     * @param body     JSON object with optional key {@code teacherPreferences}
     * @return 200 OK with updated avatar; 400 if preferences exceed 500 chars
     */
    @PatchMapping("/{avatarId}/teacher-preferences")
    public ResponseEntity<ApiResponse<AvatarResponse>> updateTeacherPreferences(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody Map<String, String> body
    ) {
        String prefs = body.getOrDefault("teacherPreferences", "");
        if (prefs.length() > 500) {
            throw new BusinessException("Teacher preferences must be under 500 characters", 400);
        }
        Avatar avatar = updateAvatarSettingsUseCase.updateTeacherPreferences(
                avatarId, userId, prefs.isBlank() ? null : prefs);
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }
}
