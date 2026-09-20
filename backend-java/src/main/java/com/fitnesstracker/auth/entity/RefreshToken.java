package com.fitnesstracker.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A persisted refresh token.
 *
 * <p>The primary key is deliberately <em>not</em> generated: it is the refresh JWT's
 * {@code jti} claim, so that a presented token can be looked up by id. The column has no
 * database default for the same reason. Adding {@code @GeneratedValue} here would break
 * refresh entirely.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(UUID jti, UUID userId, String tokenHash, Instant expiresAt) {
        this.id = jti;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    /** Recorded for session auditing; truncated to the column width the schema declares. */
    public void setClientMetadata(String userAgent, String ipAddress) {
        this.userAgent = userAgent == null || userAgent.length() <= 512
                ? userAgent
                : userAgent.substring(0, 512);
        this.ipAddress = ipAddress;
    }

    public String getTokenHashForComparison() {
        return tokenHash;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
