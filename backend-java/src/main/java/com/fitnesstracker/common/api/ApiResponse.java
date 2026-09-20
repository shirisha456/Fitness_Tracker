package com.fitnesstracker.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The success envelope every endpoint returns: {@code {"data": ..., "message"?: "..."}}.
 *
 * <p>{@code message} is omitted when absent — the previous implementation only includes it on the
 * handful of routes that set one. {@code data} is always present, and may be null.
 *
 * @param data the payload
 * @param message optional human-readable note, omitted from JSON when null
 */
public record ApiResponse<T>(T data, @JsonInclude(JsonInclude.Include.NON_NULL) String message) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> of(T data, String message) {
        return new ApiResponse<>(data, message);
    }
}
