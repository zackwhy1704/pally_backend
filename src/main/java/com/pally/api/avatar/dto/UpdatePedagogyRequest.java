package com.pally.api.avatar.dto;

import com.pally.domain.avatar.Avatar;
import jakarta.validation.constraints.NotNull;

public record UpdatePedagogyRequest(
        @NotNull(message = "mode is required") Avatar.PedagogyMode mode
) {}
