package com.fitnesstracker.auth.dto;

/**
 * The login/refresh payload.
 *
 * <p>{@code tokenType} is always {@code "bearer"} and {@code expiresIn} is the access
 * token's remaining lifetime in seconds — the Next.js BFF uses it as the access cookie's
 * {@code maxAge}.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {

    public static TokenResponse of(
            String accessToken, String refreshToken, long expiresIn, UserResponse user) {
        return new TokenResponse(accessToken, refreshToken, "bearer", expiresIn, user);
    }
}
