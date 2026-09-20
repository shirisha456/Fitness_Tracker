package com.fitnesstracker.common.api;

/**
 * The closed set of {@code error.code} values the API emits.
 *
 * <p>Mirrors the previous implementation exactly (see docs/api.md). Constants rather than an enum
 * because they are written into JSON as-is and compared as strings by clients.
 */
public final class ErrorCode {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String BAD_GATEWAY = "BAD_GATEWAY";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCode() {}
}
