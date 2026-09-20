package com.fitnesstracker.workouts.repository;

import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExerciseRepository extends JpaRepository<Exercise, UUID> {

    List<Exercise> findByCategoryOrderByName(ExerciseCategory category);

    @Query("SELECT e FROM Exercise e WHERE e.category = :category")
    List<Exercise> findByCategoryJpql(@Param("category") ExerciseCategory category);

    List<Exercise> findAllByOrderByName();

    /** Case-insensitive lookup, backing the get-or-create on POST /exercises. */
    @Query("SELECT e FROM Exercise e WHERE lower(e.name) = lower(:name)")
    Optional<Exercise> findByNameIgnoreCase(@Param("name") String name);

    /**
     * The exercises safe to place in this user's AI prompt: the curated library plus their
     * own.
     *
     * <p>Exercise names are free text any authenticated user can write, and they are
     * concatenated into the AI system prompt — an unscoped list would let one user inject
     * instructions into another user's prompt. The exercise <em>picker</em> is deliberately
     * unscoped; whether custom exercises are shared is a product decision, this is a
     * security boundary.
     */
    @Query("SELECT e FROM Exercise e "
            + "WHERE e.createdByUserId IS NULL OR e.createdByUserId = :userId "
            + "ORDER BY e.name")
    List<Exercise> findVisibleForPrompt(@Param("userId") UUID userId);
}
