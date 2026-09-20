package com.fitnesstracker.auth.dto;

import com.fitnesstracker.auth.entity.User;
import java.time.Instant;
import java.util.UUID;

/** Serialises as {@code {id, email, email_verified, role, created_at}}. */
public record UserResponse(
        UUID id, String email, boolean emailVerified, String role, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getRole().getValue(),
                user.getCreatedAt());
    }
}
