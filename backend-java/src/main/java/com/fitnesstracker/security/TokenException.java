package com.fitnesstracker.security;

import com.fitnesstracker.common.api.ErrorCode;

/**
 * A JWT that could not be trusted.
 *
 * <p>Carries the error code the previous implementation would have returned: {@code TOKEN_EXPIRED}
 * for an expired token and {@code UNAUTHORIZED} for everything else (bad signature,
 * malformed, wrong {@code type}). Both map to HTTP 401.
 */
public class TokenException extends RuntimeException {

    private final String code;

    private TokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static TokenException expired() {
        return new TokenException(ErrorCode.TOKEN_EXPIRED, "Token has expired");
    }

    public static TokenException invalid() {
        return new TokenException(ErrorCode.UNAUTHORIZED, "Invalid token");
    }

    public static TokenException invalidType() {
        return new TokenException(ErrorCode.UNAUTHORIZED, "Invalid token type");
    }

    public String getCode() {
        return code;
    }
}
