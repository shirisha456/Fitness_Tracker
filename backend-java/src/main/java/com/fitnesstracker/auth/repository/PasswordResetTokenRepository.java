package com.fitnesstracker.auth.repository;

import com.fitnesstracker.auth.entity.PasswordResetToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PasswordResetToken t
               SET t.usedAt = :now
             WHERE t.userId = :userId
               AND t.usedAt IS NULL
            """)
    int invalidateOutstanding(@Param("userId") UUID userId, @Param("now") Instant now);
}
