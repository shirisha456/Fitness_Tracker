package com.fitnesstracker.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The previous implementation's password rule, preserved exactly:
 *
 * <pre>{@code (?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,}}</pre>
 *
 * <p>The message is Jakarta's to render, but the accept/reject decision must match
 * character for character — a password a user registered with under the reference implementation has to keep
 * working, and one the reference implementation rejects must not become acceptable.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default
            "Password must be at least 8 characters and include uppercase, lowercase, "
                    + "digit, and special character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
