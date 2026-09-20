package com.fitnesstracker.workouts.repository;

import com.fitnesstracker.workouts.entity.Workout;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkoutRepository extends JpaRepository<Workout, UUID> {

    /**
     * Detail view: one query, with the children and their library rows fetch-joined.
     *
     * <p>Without the joins this would be three round trips (workout, children, exercise per
     * child) — the classic N+1. {@code open-in-view=false} means a lazy access after the
     * service returns would throw instead of quietly doing that, so the fetch is explicit.
     */
    @Query("""
            SELECT DISTINCT w FROM Workout w
            LEFT JOIN FETCH w.exercises e
            LEFT JOIN FETCH e.exercise
            WHERE w.id = :id
            """)
    Optional<Workout> findByIdWithExercises(@Param("id") UUID id);

    /**
     * List view: summaries only, aggregated in SQL.
     *
     * <p>The Python endpoint returns {@code exercise_count}, not the children, so counting
     * in the database avoids loading collections purely to call {@code size()} on them.
     * Uses {@code ix_workouts_user_performed (user_id, performed_at)}.
     *
     * <p>The casts around the optional bounds are load-bearing: PostgreSQL cannot infer a
     * parameter's type from {@code ? IS NULL} alone and fails the statement with
     * "could not determine data type of parameter". Naming the type fixes it without
     * splitting this into four near-identical queries.
     */
    @Query("""
            SELECT new com.fitnesstracker.workouts.dto.WorkoutSummaryResponse(
                       w.id, w.name, w.performedAt, count(e.id))
              FROM Workout w
              LEFT JOIN w.exercises e
             WHERE w.userId = :userId
               AND (cast(:dateFrom as LocalDate) IS NULL OR w.performedAt >= :dateFrom)
               AND (cast(:dateTo   as LocalDate) IS NULL OR w.performedAt <= :dateTo)
             GROUP BY w.id, w.name, w.performedAt, w.createdAt
             ORDER BY w.performedAt DESC, w.createdAt DESC
            """)
    List<com.fitnesstracker.workouts.dto.WorkoutSummaryResponse> findSummaries(
            @Param("userId") UUID userId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);
}
