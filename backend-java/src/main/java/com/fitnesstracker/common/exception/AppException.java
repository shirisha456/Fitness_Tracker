package com.fitnesstracker.common.exception;

import com.fitnesstracker.common.api.ErrorResponse.ErrorDetail;
import java.util.List;

/**
 * The application's single error type, mirroring the documented error contract.
 *
 * <p>Carries the wire-level {@code code} and HTTP status explicitly so that services
 * decide the contract rather than the exception-handler guessing from an exception class.
 */
public class AppException extends RuntimeException {

    private final String code;
    private final int status;
    private final List<ErrorDetail> details;

    public AppException(String code, String message, int status) {
        this(code, message, status, List.of());
    }

    public AppException(String code, String message, int status, List<ErrorDetail> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    public int getStatus() {
        return status;
    }

    public List<ErrorDetail> getDetails() {
        return details;
    }
}
