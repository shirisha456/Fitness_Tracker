package com.fitnesstracker.auth.repository;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.entity.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /** Derived query over a native enum column — part of what the enum spike proves. */
    List<User> findByRole(UserRole role);

    long countByRole(UserRole role);
}
