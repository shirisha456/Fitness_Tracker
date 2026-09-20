package com.fitnesstracker.auth.repository;

import com.fitnesstracker.auth.entity.EmailVerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Issuing a new link invalidates any outstanding one, as the Python service does. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE EmailVerificationToken t
               SET t.usedAt = :now
             WHERE t.userId = :userId
               AND t.usedAt IS NULL
            """)
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);
}
