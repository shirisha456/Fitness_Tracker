package com.fitnesstracker.auth.service;

import com.fitnesstracker.auth.dto.TokenResponse;
import com.fitnesstracker.auth.dto.UserResponse;
import com.fitnesstracker.auth.entity.RefreshToken;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.repository.RefreshTokenRepository;
import com.fitnesstracker.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints an access/refresh pair and persists the refresh token.
 *
 * <p>This lives in its own bean for two reasons, both of which were defects before it was
 * extracted.
 *
 * <p><b>1. The transaction was not being applied.</b> {@code AuthService.login} called
 * {@code issueTokenPair} as {@code this.issueTokenPair(...)}. Spring's {@code @Transactional}
 * works through a proxy, and a self-invocation never crosses it — so the annotation on that
 * method did nothing when reached from login. The same trap was already documented for
 * refresh-token reuse revocation; it was present here too and had not been noticed, because
 * the repository's own transaction made the write succeed anyway.
 *
 * <p><b>2. The connection was held across Argon2.</b> With {@code @Transactional} on
 * {@code login}, a pooled connection was acquired before the password check and held for the
 * whole method — including the Argon2id verification, which is ~100ms of pure CPU that needs
 * no database at all. Under load that capped concurrent logins at the pool size and starved
 * every other endpoint of connections. Measured: 3,285 threads pending on the pool, 3,775
 * acquisition timeouts, 30.9s worst-case acquire.
 *
 * <p>Splitting it means login now reads the user, releases the connection, spends its CPU on
 * Argon2 holding nothing, and only then opens this short write transaction.
 */
@Service
public class TokenIssuanceService {

    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokenService;
    private final Clock clock;

    public TokenIssuanceService(
            RefreshTokenRepository refreshTokens, TokenService tokenService, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    /**
     * REQUIRED, not REQUIRES_NEW: rotation calls this from inside its own transaction and
     * must stay atomic with the revocation it just performed.
     */
    @Transactional
    public TokenResponse issue(User user, String userAgent, String ipAddress) {
        Instant now = Instant.now(clock);
        TokenService.IssuedToken access = tokenService.issueAccessToken(user);
        TokenService.IssuedToken refresh = tokenService.issueRefreshToken(user.getId());

        // The JWT's jti IS the primary key — that is how a presented token is looked up.
        RefreshToken stored = new RefreshToken(
                refresh.jti(),
                user.getId(),
                TokenService.hashToken(refresh.token()),
                refresh.expiresAt());
        stored.setClientMetadata(userAgent, ipAddress);
        refreshTokens.save(stored);

        return TokenResponse.of(
                access.token(),
                refresh.token(),
                access.expiresInSeconds(now),
                UserResponse.from(user));
    }
}
