package com.fitnesstracker.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email String email, @NotBlank @Size(max = 128) String password) {


    /**
     * Trims and lowercases before validation runs, matching the Python validator — which
     * accepts {@code " Alex@Example.COM "} and stores {@code alex@example.com}. Doing it
     * in the compact constructor means {@code @Email} sees the normalised value, so the
     * two backends agree on what is acceptable, not just on what is stored.
     */
    public LoginRequest {
        email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String normalisedEmail() {
        return email;
    }
}
