package com.pally.api.avatar;

import com.pally.api.avatar.dto.AvatarListResponse;
import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.api.avatar.dto.CreateAvatarRequest;
import com.pally.api.avatar.dto.UpdateGradeCurriculumRequest;
import com.pally.api.avatar.dto.UpdatePedagogyRequest;
import com.pally.api.avatar.dto.UpdateTestDateRequest;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.usecase.CreateAvatarUseCase;
import com.pally.domain.avatar.usecase.DeleteAvatarUseCase;
import com.pally.domain.avatar.usecase.GetAvatarUseCase;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody CreateAvatarRequest request
    ) {
        Avatar avatar = createAvatarUseCase.execute(
                userId, request.name(), request.subject(), request.characterType(),
                request.gradeLevel(), request.curriculumType()
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
            @RequestHeader("X-User-Id") String userId
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
            @RequestHeader("X-User-Id") String userId,
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
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId
    ) {
        deleteAvatarUseCase.execute(avatarId, userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{avatarId}/pedagogy")
    public ResponseEntity<ApiResponse<AvatarResponse>> updatePedagogy(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody UpdatePedagogyRequest request
    ) {
        Avatar avatar = updateAvatarSettingsUseCase.updatePedagogy(avatarId, userId, request.mode());
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }

    @PatchMapping("/{avatarId}/grade")
    public ResponseEntity<ApiResponse<AvatarResponse>> updateGradeCurriculum(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody UpdateGradeCurriculumRequest request
    ) {
        Avatar avatar = updateAvatarSettingsUseCase.updateGradeCurriculum(
                avatarId, userId, request.gradeLevel(), request.curriculumType());
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }

    @PatchMapping("/{avatarId}/test-date")
    public ResponseEntity<ApiResponse<AvatarResponse>> updateTestDate(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody UpdateTestDateRequest request
    ) {
        Avatar avatar = updateAvatarSettingsUseCase.updateTestDate(avatarId, userId, request.testDate());
        return ResponseEntity.ok(ApiResponse.success(avatarMapper.toResponse(avatar)));
    }
}
