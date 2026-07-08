package com.pally.api.auth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Completes a PENDING_PROFILE / legacy null-birthYear account: the missing birth year
 *  (required) and, only if under-13, a parent email. */
public record CompleteProfileRequest(
    @NotNull @Min(1950) Integer birthYear,
    String parentEmail
) {}
