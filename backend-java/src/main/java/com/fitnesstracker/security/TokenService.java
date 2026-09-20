package com.fitnesstracker.security;

import com.fitnesstracker.auth.entity.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the JWTs the previous implementation already issues.
 *
 * <p>Compatibility is the whole job here, so the claim set is reproduced exactly (see
 * {@code app/core/security.py}):
 *
 * <table>
 *   <tr><th>Claim</th><th>Access</th><th>Refresh</th></tr>
 *   <tr><td>{@code sub}</td><td>user id</td><td>user id</td></tr>
 *   <tr><td>{@code type}</td><td>{@code "access"}</td><td>{@code "refresh"}</td></tr>
 *   <tr><td>{@code jti}</td><td>random UUID</td><td>random UUID — also the refresh_tokens PK</td></tr>
 *   <tr><td>{@code iat}/{@code exp}</td><td>NumericDate</td><td>NumericDate</td></tr>
 *   <tr><td>{@code role}</td><td>yes</td><td>—</td></tr>
 *   <tr><td>{@code email_verified}</td><td>yes</td><td>—</td></tr>
 * </table>
 *
 * <p>No {@code iss} and no {@code aud}: the previous implementation sets neither, and adding
 * them here would produce tokens it rejects. HS256 over the raw UTF-8 bytes of
 * {@code SECRET_KEY}, matching PyJWT.
 */
@Service
public class TokenService {

    /**
     * RFC 7518 §3.2 requires an HMAC key at least as long as the hash output. Nimbus
     * enforces it; PyJWT only warns. A secret between the previous 16-character minimum and
     * 32 bytes is therefore accepted by the reference implementation and rejected here — so it is checked at
     * startup with an actionable message rather than on the first login.
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final Clock clock;
    private final JWSSigner signer;
    private final JWSVerifier verifier;

    public TokenService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        byte[] key = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "SECRET_KEY must be at least " + MIN_SECRET_BYTES + " bytes for HS256 "
                            + "(RFC 7518 section 3.2); it is " + key.length
                            + ". The previous implementation only warns about this. Both backends "
                            + "must share the same value, so lengthen it in .env and "
                            + "restart both.");
        }
        try {
            this.signer = new MACSigner(key);
            this.verifier = new MACVerifier(key);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not initialise HS256 signer", ex);
        }
    }

    @PostConstruct
    void logConfiguration() {
        // The secret itself is never logged, only its shape.
        org.slf4j.LoggerFactory.getLogger(TokenService.class).info(
                "jwt_configured alg=HS256 access_ttl_minutes={} refresh_ttl_days={}",
                properties.accessTokenExpireMinutes(), properties.refreshTokenExpireDays());
    }

    public record IssuedToken(String token, UUID jti, Instant expiresAt) {

        /** Seconds until expiry, as the {@code expires_in} field of the login response. */
        public long expiresInSeconds(Instant now) {
            return Duration.between(now, expiresAt).toSeconds();
        }
    }

    public IssuedToken issueAccessToken(User user) {
        Instant now = Instant.now(clock);
        Instant expiry = now.plus(Duration.ofMinutes(properties.accessTokenExpireMinutes()));
        UUID jti = UUID.randomUUID();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().getValue())
                .claim("email_verified", user.isEmailVerified())
                .claim("type", TokenType.ACCESS.claimValue())
                .jwtID(jti.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        return new IssuedToken(sign(claims), jti, expiry);
    }

    public IssuedToken issueRefreshToken(UUID userId) {
        Instant now = Instant.now(clock);
        Instant expiry = now.plus(Duration.ofDays(properties.refreshTokenExpireDays()));
        UUID jti = UUID.randomUUID();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("type", TokenType.REFRESH.claimValue())
                .jwtID(jti.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();

        return new IssuedToken(sign(claims), jti, expiry);
    }

    /**
     * Verifies signature, expiry and the {@code type} claim, in that order.
     *
     * @throws TokenException with the same error code the previous implementation would return
     */
    public ParsedToken parse(String rawToken, TokenType expected) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {
            jwt = SignedJWT.parse(rawToken);
            if (!jwt.verify(verifier)) {
                throw TokenException.invalid();
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | JOSEException | IllegalStateException ex) {
            throw TokenException.invalid();
        }

        Date expiry = claims.getExpirationTime();
        if (expiry == null || expiry.toInstant().isBefore(Instant.now(clock))) {
            throw TokenException.expired();
        }

        Object type = claims.getClaim("type");
        if (!expected.claimValue().equals(type)) {
            throw TokenException.invalidType();
        }

        UUID subject = parseUuid(claims.getSubject());
        UUID jti = parseUuid(claims.getJWTID());
        if (subject == null || jti == null) {
            throw TokenException.invalid();
        }
        return new ParsedToken(subject, jti, expiry.toInstant());
    }

    public record ParsedToken(UUID userId, UUID jti, Instant expiresAt) {}

    private String sign(JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign token", ex);
        }
        return jwt.serialize();
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** SHA-256 hex, matching {@code app/core/security.py::hash_token}. Raw tokens are never stored. */
    public static String hashToken(String rawToken) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
