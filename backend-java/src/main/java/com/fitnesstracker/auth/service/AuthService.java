package com.fitnesstracker.auth.service;

import com.fitnesstracker.auth.dto.ForgotPasswordRequest;
import com.fitnesstracker.auth.dto.LoginRequest;
import com.fitnesstracker.auth.dto.RegisterRequest;
import com.fitnesstracker.auth.dto.ResetPasswordRequest;
import com.fitnesstracker.auth.dto.TokenResponse;
import com.fitnesstracker.auth.dto.UserResponse;
import com.fitnesstracker.auth.entity.EmailVerificationToken;
import com.fitnesstracker.auth.entity.PasswordResetToken;
import com.fitnesstracker.auth.entity.RefreshToken;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.entity.UserRole;
import com.fitnesstracker.auth.repository.EmailVerificationTokenRepository;
import com.fitnesstracker.auth.repository.PasswordResetTokenRepository;
import com.fitnesstracker.auth.repository.RefreshTokenRepository;
import com.fitnesstracker.auth.repository.UserRepository;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.api.ErrorResponse.ErrorDetail;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.notifications.EmailKind;
import com.fitnesstracker.notifications.EmailDispatcher;
import com.fitnesstracker.notifications.EmailRequest;
import com.fitnesstracker.security.TokenService;
import com.fitnesstracker.security.TokenType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, verification and password reset.
 *
 * <p>A direct port of {@code app/modules/auth/service.py}. Where a behaviour looks odd it
 * is almost certainly deliberate in the original and is preserved with a note; the Python
 * implementation is the specification until parity is proven.
 *
 * <p>Refresh rotation lives in {@link TokenRotationService} because its transaction
 * boundaries are subtle enough to deserve their own class.
 */
@Service
public class AuthService {

    /** 32 random bytes, URL-safe base64 — matches {@code secrets.token_urlsafe(32)}. */
    private static final int ONE_TIME_TOKEN_BYTES = 32;

    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String ACCOUNT_DEACTIVATED = "Account is deactivated";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenIssuanceService tokenIssuance;
    private final EmailVerificationTokenRepository verificationTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final EmailDispatcher emailDispatcher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private final Duration verificationTtl;
    private final Duration resetTtl;

    /**
     * A precomputed hash so a login for an unknown email still performs a full Argon2
     * verification. Without it the response time reveals whether an account exists.
     * The Python service does the same thing with {@code _DUMMY_PASSWORD_HASH}.
     */
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            EmailVerificationTokenRepository verificationTokens,
            PasswordResetTokenRepository resetTokens,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            EmailDispatcher emailDispatcher,
            TokenIssuanceService tokenIssuance,
            Clock clock,
            @Value("${app.auth.email-verification-expire-hours:24}") int verificationHours,
            @Value("${app.auth.password-reset-expire-hours:1}") int resetHours) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.verificationTokens = verificationTokens;
        this.resetTokens = resetTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.emailDispatcher = emailDispatcher;
        this.tokenIssuance = tokenIssuance;
        this.clock = clock;
        this.verificationTtl = Duration.ofHours(verificationHours);
        this.resetTtl = Duration.ofHours(resetHours);
        this.dummyHash = passwordEncoder.encode("timing-safe-dummy-password");
    }

    // --- registration --------------------------------------------------------

    @Transactional
    public UserResponse register(RegisterRequest request) {
        requirePasswordsMatch(request.password(), request.passwordConfirm());

        String email = request.normalisedEmail();
        if (users.findByEmail(email).isPresent()) {
            throw emailAlreadyRegistered();
        }

        User user = new User(email, passwordEncoder.encode(request.password()), UserRole.USER);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent registration for the same address.
            throw emailAlreadyRegistered();
        }

        String rawToken = issueVerificationToken(user);
        emailDispatcher.enqueue(new EmailRequest(EmailKind.VERIFICATION, user.getEmail(), rawToken));
        return UserResponse.from(user);
    }

    // --- login ---------------------------------------------------------------

    /**
     * Deliberately NOT {@code @Transactional}.
     *
     * <p>Argon2id verification is ~100ms of CPU and touches no database. Holding a pooled
     * connection across it caps concurrent logins at the pool size and starves every other
     * endpoint — measured under load as 3,285 threads pending on the pool and a 30.9s
     * worst-case acquisition. The user lookup runs in the repository's own short read
     * transaction, the password check holds nothing, and the write opens its own transaction
     * in {@link TokenIssuanceService}.
     *
     * <p>Nothing here needs to be atomic with anything else: a login either issues a token
     * pair or it does not.
     */
    public TokenResponse login(LoginRequest request, String userAgent, String ipAddress) {
        Optional<User> candidate = users.findByEmail(request.normalisedEmail());

        // The hash to verify against is chosen before the outcome is known, so the
        // expensive Argon2 comparison runs on both paths.
        String hash = candidate
                .map(User::getPasswordHash)
                .filter(stored -> stored != null)
                .orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        User user = candidate.orElse(null);
        if (user == null || user.getPasswordHash() == null || !passwordMatches) {
            throw new AppException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS, 401);
        }
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw new AppException(ErrorCode.FORBIDDEN, ACCOUNT_DEACTIVATED, 403);
        }

        return issueTokenPair(user, userAgent, ipAddress);
    }

    /**
     * Mints a pair and persists the refresh token. Shared by login and rotation.
     *
     * <p>Delegates to {@link TokenIssuanceService} rather than doing the work here, so the
     * transaction is applied even when the caller is {@code login} in this same bean — a
     * self-invocation does not pass through the proxy, so an annotation here would not fire.
     */
    public TokenResponse issueTokenPair(User user, String userAgent, String ipAddress) {
        return tokenIssuance.issue(user, userAgent, ipAddress);
    }

    // --- logout --------------------------------------------------------------

    /**
     * Idempotent by design: an invalid, unknown or already-revoked token still succeeds.
     * The caller is trying to end a session, and telling them the token was already dead
     * is neither useful nor safe.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        try {
            var parsed = tokenService.parse(rawRefreshToken, TokenType.REFRESH);
            refreshTokens.findById(parsed.jti())
                    .filter(token -> !token.isRevoked())
                    .ifPresent(token -> token.revoke(Instant.now(clock)));
        } catch (RuntimeException ex) {
            // Already logged out, as far as the caller is concerned.
        }
    }

    // --- email verification --------------------------------------------------

    @Transactional
    public boolean verifyEmail(String rawToken) {
        EmailVerificationToken token = verificationTokens
                .findByTokenHash(TokenService.hashToken(rawToken))
                .orElseThrow(() -> badRequest("Invalid or expired verification token"));

        if (token.isUsed()) {
            throw badRequest("Verification token has already been used");
        }
        Instant now = Instant.now(clock);
        if (token.isExpired(now)) {
            throw badRequest("Verification token has expired");
        }

        User user = activeUser(token.getUserId())
                .orElseThrow(() -> badRequest("Invalid or expired verification token"));

        user.setEmailVerified(true);
        token.markUsed(now);
        return true;
    }

    @Transactional
    public void resendVerification(User user) {
        if (user.isEmailVerified()) {
            throw badRequest("Email is already verified");
        }
        String rawToken = issueVerificationToken(user);
        emailDispatcher.enqueue(new EmailRequest(EmailKind.VERIFICATION, user.getEmail(), rawToken));
    }

    private String issueVerificationToken(User user) {
        Instant now = Instant.now(clock);
        verificationTokens.invalidateOutstanding(user.getId(), now);
        String rawToken = generateOneTimeToken();
        verificationTokens.save(new EmailVerificationToken(
                user.getId(), TokenService.hashToken(rawToken), now.plus(verificationTtl)));
        return rawToken;
    }

    // --- password reset ------------------------------------------------------

    /**
     * Always succeeds from the caller's point of view — an unknown address and a known one
     * are indistinguishable, so the endpoint cannot be used to enumerate accounts.
     */
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        Optional<User> candidate = users.findByEmail(request.normalisedEmail())
                .filter(user -> user.isActive() && user.getDeletedAt() == null)
                // An account with no password hash is OAuth-only; there is nothing to reset.
                .filter(user -> user.getPasswordHash() != null);
        if (candidate.isEmpty()) {
            return;
        }

        User user = candidate.get();
        Instant now = Instant.now(clock);
        resetTokens.invalidateOutstanding(user.getId(), now);

        String rawToken = generateOneTimeToken();
        resetTokens.save(new PasswordResetToken(
                user.getId(), TokenService.hashToken(rawToken), now.plus(resetTtl)));
        emailDispatcher.enqueue(new EmailRequest(EmailKind.PASSWORD_RESET, user.getEmail(), rawToken));
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        requirePasswordsMatch(request.password(), request.passwordConfirm());

        PasswordResetToken token = resetTokens
                .findByTokenHash(TokenService.hashToken(request.token()))
                .orElseThrow(() -> badRequest("Invalid or expired reset token"));

        if (token.isUsed()) {
            throw badRequest("Reset token has already been used");
        }
        Instant now = Instant.now(clock);
        if (token.isExpired(now)) {
            throw badRequest("Reset token has expired");
        }

        User user = activeUser(token.getUserId())
                .orElseThrow(() -> badRequest("Invalid or expired reset token"));

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        token.markUsed(now);
        // Changing a password ends every existing session, everywhere.
        refreshTokens.revokeAllActiveForUser(user.getId(), now);
    }

    // --- helpers -------------------------------------------------------------

    private Optional<User> activeUser(java.util.UUID userId) {
        return users.findById(userId)
                .filter(user -> user.isActive() && user.getDeletedAt() == null);
    }

    private String generateOneTimeToken() {
        byte[] bytes = new byte[ONE_TIME_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void requirePasswordsMatch(String password, String confirmation) {
        if (password == null || !password.equals(confirmation)) {
            throw new AppException(
                    ErrorCode.VALIDATION_ERROR,
                    "Request validation failed",
                    400,
                    List.of(new ErrorDetail(
                            "password_confirm", "Passwords do not match", "INVALID_FORMAT")));
        }
    }

    private static AppException emailAlreadyRegistered() {
        return new AppException(
                ErrorCode.CONFLICT, "An account with this email already exists", 409);
    }

    private static AppException badRequest(String message) {
        return new AppException(ErrorCode.BAD_REQUEST, message, 400);
    }
}
