package com.fitnesstracker.auth.service;

import com.fitnesstracker.auth.dto.TokenResponse;
import com.fitnesstracker.auth.entity.RefreshToken;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.repository.RefreshTokenRepository;
import com.fitnesstracker.auth.repository.UserRepository;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.security.TokenService;
import com.fitnesstracker.security.TokenType;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-token rotation with reuse detection.
 *
 * <p>A port of {@code app/modules/auth/service.py::refresh_tokens}, in the same order,
 * because the order is the security property:
 *
 * <ol>
 *   <li>decode and require {@code type == "refresh"} → 401
 *   <li>look the token up by its {@code jti}; missing, or belonging to another user → 401
 *   <li><b>already revoked → reuse.</b> Revoke the whole session family in a committed
 *       transaction, then 401
 *   <li>expired → 401
 *   <li>stored hash mismatch → 401
 *   <li>user inactive or deleted → 403
 *   <li>otherwise rotate: revoke the presented token, issue a fresh pair
 * </ol>
 *
 * <p>Step 3 is the one that bites. The 401 rolls this transaction back, so revoking
 * inline would undo the revocation — the system would detect the attack, report it, and
 * do nothing. {@link TokenRevocationService} commits separately via {@code REQUIRES_NEW},
 * and it is a separate bean because Spring's proxy-based {@code @Transactional} is a no-op
 * on self-invocation. This exact bug was found and fixed once already in the reference
 * implementation (commit 6c32800); {@code RefreshTokenReuseTransactionSpikeTest} guards it.
 */
@Service
public class TokenRotationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRotationService.class);

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final TokenRevocationService revocation;
    private final TokenService tokenService;
    private final AuthService authService;
    private final Clock clock;

    public TokenRotationService(
            RefreshTokenRepository refreshTokens,
            UserRepository users,
            TokenRevocationService revocation,
            TokenService tokenService,
            AuthService authService,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.revocation = revocation;
        this.tokenService = tokenService;
        this.authService = authService;
        this.clock = clock;
    }

    @Transactional
    public TokenResponse rotate(String rawRefreshToken, String userAgent, String ipAddress) {
        TokenService.ParsedToken parsed = tokenService.parse(rawRefreshToken, TokenType.REFRESH);

        RefreshToken stored = refreshTokens.findById(parsed.jti()).orElse(null);
        if (stored == null || !stored.getUserId().equals(parsed.userId())) {
            throw unauthorized("Invalid token");
        }

        if (stored.isRevoked()) {
            int revoked = revocation.revokeAllForUserInNewTransaction(stored.getUserId());
            // No token material in the log line — only the user id and a count.
            log.warn("refresh_token_reuse_detected user_id={} jti={} sibling_tokens_revoked={}",
                    stored.getUserId(), parsed.jti(), revoked);
            throw unauthorized("Refresh token reuse detected. Please log in again.");
        }

        Instant now = Instant.now(clock);
        if (stored.getExpiresAt().isBefore(now)) {
            throw unauthorized("Refresh token expired");
        }
        if (!constantTimeEquals(
                stored.getTokenHashForComparison(), TokenService.hashToken(rawRefreshToken))) {
            throw unauthorized("Invalid token");
        }

        Optional<User> user = users.findById(parsed.userId())
                .filter(candidate -> candidate.isActive() && candidate.getDeletedAt() == null);
        if (user.isEmpty()) {
            throw new AppException(ErrorCode.FORBIDDEN, "Account is deactivated", 403);
        }

        stored.revoke(now);
        return authService.issueTokenPair(user.get(), userAgent, ipAddress);
    }

    /**
     * The hashes are not secrets, but comparing them in constant time costs nothing and
     * removes a timing signal from an endpoint that takes attacker-controlled input.
     */
    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static AppException unauthorized(String message) {
        return new AppException(ErrorCode.UNAUTHORIZED, message, 401);
    }
}
