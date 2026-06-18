package com.pally.api.demo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record DemoRequestBody(
    @NotBlank String orgName,
    @NotBlank String contactName,
    @NotBlank @Email String email,
    @NotBlank String phone,
    String segment,        // optional: SOLO | CENTRE | SCHOOL
    Integer estClasses,    // optional: estimated number of classes
    Integer estStudents    // optional: estimated number of students
) {}
