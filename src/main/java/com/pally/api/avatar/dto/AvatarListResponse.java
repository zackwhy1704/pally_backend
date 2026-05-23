package com.pally.api.avatar.dto;

import java.util.List;

/**
 * Response body for a list of avatars.
 *
 * @param avatars list of avatar response objects
 */
public record AvatarListResponse(List<AvatarResponse> avatars) {}
