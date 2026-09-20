package com.fitnesstracker.auth.dto;

import com.fitnesstracker.auth.entity.User;
import java.time.Instant;
import java.util.UUID;

/** {@code GET /auth/me}. Adds {@code has_profile} to the user shape. */
public record MeResponse(
        UUID id,
        String email,
        boolean emailVerified,
        String role,
        boolean hasProfile,
        Instant createdAt) {

    public static MeResponse from(User user, boolean hasProfile) {
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getRole().getValue(),
                hasProfile,
                user.getCreatedAt());
    }
}
