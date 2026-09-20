package com.fitnesstracker.auth.dto;

import com.fitnesstracker.common.validation.StrongPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email lowercased and trimmed before use, as the documented contract requires
 * @param password 8–128 characters, {@link StrongPassword}
 * @param passwordConfirm must equal {@code password}; checked in the service so the
 *     mismatch is reported as a validation detail rather than a field constraint
 */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) @StrongPassword String password,
        @NotBlank @Size(min = 8, max = 128) String passwordConfirm) {


    /**
     * Trims and lowercases before validation runs, matching the documented contract — which
     * accepts {@code " Alex@Example.COM "} and stores {@code alex@example.com}. Doing it
     * in the compact constructor means {@code @Email} sees the normalised value, so the
     * two backends agree on what is acceptable, not just on what is stored.
     */
    public RegisterRequest {
        email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String normalisedEmail() {
        return email;
    }
}
