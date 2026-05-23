package com.pally.api.avatar.dto;

public record UpdateGradeCurriculumRequest(
        String gradeLevel,
        String curriculumType
) {}
