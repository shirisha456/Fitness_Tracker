package com.fitnesstracker.auth.service;

import com.fitnesstracker.auth.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes refresh tokens in a transaction of its own.
 *
 * <p>Separated from {@link TokenRotationService} for one reason: {@code REQUIRES_NEW}.
 * When refresh-token reuse is detected the caller must revoke the whole session family
 * and then fail the request with a 401. If the revocation ran in the caller's
 * transaction, the exception that reports the reuse would roll the revocation back — the
 * system would detect the attack, report it, and do nothing about it.
 *
 * <p>The reference implementation solved the same problem by committing explicitly before
 * raising; this is the Spring equivalent. It is a separate bean because Spring's
 * proxy-based {@code @Transactional} is a no-op on a self-invocation.
 */
@Service
public class TokenRevocationService {

    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public TokenRevocationService(RefreshTokenRepository refreshTokens, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    /**
     * Revokes every active token for a user and commits independently of the caller.
     *
     * @return how many tokens were revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUserInNewTransaction(UUID userId) {
        return refreshTokens.revokeAllActiveForUser(userId, Instant.now(clock));
    }
}
