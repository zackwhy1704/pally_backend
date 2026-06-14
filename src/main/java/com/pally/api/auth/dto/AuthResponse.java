package com.pally.api.auth.dto;

import com.pally.domain.account.AccountType;

public record AuthResponse(
        String userId,
        String token,
        boolean isNewUser,
        boolean setupComplete,
        AccountType accountType
) {}
