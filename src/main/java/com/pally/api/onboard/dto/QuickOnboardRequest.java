package com.pally.api.onboard.dto;

import com.pally.domain.avatar.Subject;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QuickOnboardRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be 255 characters or fewer")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
        String password,

        @Size(max = 100, message = "Display name must be 100 characters or fewer")
        String displayName,

        @NotNull(message = "Subject is required")
        Subject subject,

        String level,

        /// Optional role — "parent" promotes to PARENT account type on registration.
        String role,

        /// Optional birth YEAR (student path). Year only — no DOB / NRIC. Server
        /// derives isUnder13; null → treated 13+. Upper bound enforced server-side.
        @Min(value = 1950, message = "Birth year must be 1950 or later")
        Integer birthYear
) {}
