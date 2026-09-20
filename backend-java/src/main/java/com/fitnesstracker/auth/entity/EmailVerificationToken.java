package com.fitnesstracker.auth.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken extends OneTimeToken {

    protected EmailVerificationToken() {
        super();
    }

    public EmailVerificationToken(UUID userId, String tokenHash, Instant expiresAt) {
        super(userId, tokenHash, expiresAt);
    }
}
