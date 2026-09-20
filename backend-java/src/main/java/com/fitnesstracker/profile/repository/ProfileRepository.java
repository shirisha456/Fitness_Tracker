package com.fitnesstracker.profile.repository;

import com.fitnesstracker.profile.entity.Profile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByUserId(UUID userId);

    /** Backs {@code has_profile} on {@code GET /auth/me}. */
    boolean existsByUserId(UUID userId);
}
