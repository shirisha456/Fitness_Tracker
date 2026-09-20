package com.fitnesstracker.support;

import com.fitnesstracker.auth.entity.RefreshToken;
import com.fitnesstracker.auth.repository.RefreshTokenRepository;
import com.fitnesstracker.common.exception.RefreshTokenReuseException;
import com.fitnesstracker.security.TokenService;
import com.fitnesstracker.security.TokenType;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <em>wrong</em> way to handle refresh-token reuse, reproduced for one regression test.
 *
 * <p>Test-only on purpose. It used to live in {@code TokenRotationService} so the Phase 0.5
 * spike could demonstrate the hazard, but shipping deliberately insecure code in the
 * production artifact — however unreachable — is not worth the convenience.
 *
 * <p>What it demonstrates: revoking the session family inside the same transaction that
 * then throws means the exception rolls the revocation back. The API still answers 401, so
 * no response-level assertion can tell the difference — the attack is detected, reported,
 * and not acted on.
 */
@TestConfiguration
public class NaiveTokenRotation {

    @Service
    public static class NaiveTokenRotationService {

        private final RefreshTokenRepository refreshTokens;
        private final TokenService tokenService;
        private final Clock clock;

        public NaiveTokenRotationService(
                RefreshTokenRepository refreshTokens, TokenService tokenService, Clock clock) {
            this.refreshTokens = refreshTokens;
            this.tokenService = tokenService;
            this.clock = clock;
        }

        /** Revokes in the caller's transaction, then throws — so the revocation is lost. */
        @Transactional
        public void rotate(String rawRefreshToken) {
            var parsed = tokenService.parse(rawRefreshToken, TokenType.REFRESH);
            RefreshToken stored = refreshTokens.findById(parsed.jti())
                    .orElseThrow(() -> new RefreshTokenReuseException("Invalid token"));

            if (stored.isRevoked()) {
                refreshTokens.revokeAllActiveForUser(stored.getUserId(), Instant.now(clock));
                throw new RefreshTokenReuseException(
                        "Refresh token reuse detected. Please log in again.");
            }
        }
    }
}
