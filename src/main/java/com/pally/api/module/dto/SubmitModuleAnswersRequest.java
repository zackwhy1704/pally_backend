package com.pally.api.module.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record SubmitModuleAnswersRequest(
        @NotEmpty List<Map<String, String>> submissions
) {}
