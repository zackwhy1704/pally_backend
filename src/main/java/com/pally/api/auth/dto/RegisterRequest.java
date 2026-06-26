package com.pally.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be 255 characters or fewer")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password must be 8–128 characters")
        String password,

        @Size(max = 100, message = "Display name must be 100 characters or fewer")
        String displayName,

        String role,

        /// Optional birth YEAR (student path only). Data minimisation: year only,
        /// never a full DOB / NRIC. The server derives isUnder13 from this; null →
        /// treated as 13+. The dynamic upper bound (≤ current year) is enforced
        /// server-side in AuthService since an annotation can't reference "now".
        @Min(value = 1950, message = "Birth year must be 1950 or later")
        Integer birthYear,

        /// Parent/guardian email — REQUIRED server-side when birthYear places the
        /// signer under 13 (PDPC 2024). Ignored for 13+ signups.
        @Email(message = "Parent email must be a valid address")
        @Size(max = 255, message = "Parent email must be 255 characters or fewer")
        String parentEmail
) {}
