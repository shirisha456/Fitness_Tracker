package com.fitnesstracker.common.exception;

/**
 * Raised when a refresh token that was already revoked is presented again.
 *
 * <p>A RuntimeException, so Spring's default rollback rules apply — which is precisely
 * the hazard the revocation path has to work around. See
 * {@link com.fitnesstracker.auth.service.TokenRotationService}.
 */
public class RefreshTokenReuseException extends RuntimeException {

    public RefreshTokenReuseException(String message) {
        super(message);
    }
}
