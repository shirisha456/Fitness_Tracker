package com.fitnesstracker.common.api;

import java.util.List;

/**
 * The error envelope, byte-compatible with the Python backend's.
 *
 * <p>{@code details} is always serialised, as {@code []} when empty — the frontend reads
 * it unconditionally.
 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(
            String code, String message, List<ErrorDetail> details, String correlationId) {}

    /** One field-level validation failure. */
    public record ErrorDetail(String field, String message, String code) {}

    public static ErrorResponse of(
            String code, String message, List<ErrorDetail> details, String correlationId) {
        return new ErrorResponse(
                new ErrorBody(code, message, details == null ? List.of() : details, correlationId));
    }
}
