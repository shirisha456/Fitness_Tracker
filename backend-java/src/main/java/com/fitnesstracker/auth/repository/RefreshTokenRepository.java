package com.fitnesstracker.auth.repository;

import com.fitnesstracker.auth.entity.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    List<RefreshToken> findByUserId(UUID userId);

    /**
     * Revokes every still-active token for a user in one statement.
     *
     * <p>A bulk update rather than a load-and-mutate loop, so that it does not depend on
     * the caller's persistence context — it is invoked from a separate transaction during
     * reuse handling, where the outer context is about to be discarded.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :now
             WHERE t.userId = :userId
               AND t.revokedAt IS NULL
            """)
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
