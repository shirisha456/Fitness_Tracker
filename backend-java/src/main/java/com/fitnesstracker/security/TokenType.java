package com.fitnesstracker.security;

/**
 * The {@code type} claim. The Python backend refuses a token whose type does not match
 * what the endpoint expects, so an access token cannot be replayed as a refresh token or
 * vice versa. That check is part of the contract, not an implementation detail.
 */
public enum TokenType {
    ACCESS("access"),
    REFRESH("refresh");

    private final String claimValue;

    TokenType(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }
}
