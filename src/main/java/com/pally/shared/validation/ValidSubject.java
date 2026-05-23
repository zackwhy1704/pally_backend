package com.pally.shared.validation;

import com.pally.domain.avatar.Subject;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Validates that a string value is a valid {@link Subject} enum name.
 */
@Documented
@Constraint(validatedBy = ValidSubject.SubjectValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSubject {

    String message() default "Subject must be one of: {validValues}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class SubjectValidator implements ConstraintValidator<ValidSubject, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) return true; // let @NotNull handle null
            boolean valid = Arrays.stream(Subject.values())
                    .anyMatch(s -> s.name().equalsIgnoreCase(value));
            if (!valid) {
                String validValues = Arrays.stream(Subject.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "Subject must be one of: " + validValues
                ).addConstraintViolation();
            }
            return valid;
        }
    }
}
