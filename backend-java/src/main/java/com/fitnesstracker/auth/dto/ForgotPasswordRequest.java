package com.fitnesstracker.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(@NotBlank @Email String email) {


    /**
     * Trims and lowercases before validation runs, matching the Python validator — which
     * accepts {@code " Alex@Example.COM "} and stores {@code alex@example.com}. Doing it
     * in the compact constructor means {@code @Email} sees the normalised value, so the
     * two backends agree on what is acceptable, not just on what is stored.
     */
    public ForgotPasswordRequest {
        email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String normalisedEmail() {
        return email;
    }
}
