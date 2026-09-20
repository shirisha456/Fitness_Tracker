package com.fitnesstracker.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    /** Copied verbatim from {@code app/modules/auth/schemas.py::PASSWORD_PATTERN}. */
    private static final Pattern PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // A null password is reported by @NotNull/@Size, not here, so that a missing field
        // does not produce two details entries where the contract produces one.
        return value == null || PATTERN.matcher(value).matches();
    }
}
