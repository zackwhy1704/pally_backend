package com.pally.api.module.dto;

import jakarta.validation.constraints.NotNull;

/// Self-assessment of an open-ended PROVE answer.
/// @param selfReport one of YES | PARTLY | NO
public record SelfReportRequest(
        @NotNull String selfReport
) {}
