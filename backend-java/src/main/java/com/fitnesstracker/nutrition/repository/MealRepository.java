package com.fitnesstracker.nutrition.repository;

import com.fitnesstracker.nutrition.entity.Meal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealRepository extends JpaRepository<Meal, UUID> {

    /** Uses {@code ix_meals_user_logged (user_id, logged_at)} for both filter and sort. */
    @Query("""
            SELECT m FROM Meal m
             WHERE m.userId = :userId
               AND (cast(:dateFrom as LocalDate) IS NULL OR m.loggedAt >= :dateFrom)
               AND (cast(:dateTo   as LocalDate) IS NULL OR m.loggedAt <= :dateTo)
             ORDER BY m.loggedAt DESC, m.createdAt DESC
            """)
    List<Meal> findForUser(
            @Param("userId") UUID userId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);

    /**
     * Daily macro totals, aggregated in SQL.
     *
     * <p>COALESCE so an empty day returns zeros rather than nulls — the Python summary does
     * the same, and the dashboard renders the number unconditionally.
     */
    @Query("""
            SELECT new com.fitnesstracker.nutrition.dto.MealTotals(
                       coalesce(sum(m.calories), 0),
                       coalesce(sum(m.proteinG), 0.0),
                       coalesce(sum(m.carbsG), 0.0),
                       coalesce(sum(m.fatG), 0.0))
              FROM Meal m
             WHERE m.userId = :userId AND m.loggedAt = :day
            """)
    com.fitnesstracker.nutrition.dto.MealTotals totalsForDay(
            @Param("userId") UUID userId, @Param("day") LocalDate day);
}
