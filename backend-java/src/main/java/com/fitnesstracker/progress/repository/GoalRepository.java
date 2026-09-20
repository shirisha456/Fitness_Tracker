package com.fitnesstracker.progress.repository;

import com.fitnesstracker.progress.entity.Goal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    List<Goal> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
