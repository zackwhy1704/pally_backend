package com.pally.api.demo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record DemoRequestBody(
    @NotBlank String orgName,
    @NotBlank String contactName,
    @NotBlank @Email String email,
    @NotBlank String phone
) {}
