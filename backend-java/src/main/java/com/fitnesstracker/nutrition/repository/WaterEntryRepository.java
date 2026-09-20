package com.fitnesstracker.nutrition.repository;

import com.fitnesstracker.nutrition.entity.WaterEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WaterEntryRepository extends JpaRepository<WaterEntry, UUID> {

    /** Sorted by creation time, not logged date — matching the previous implementation. */
    @Query("""
            SELECT w FROM WaterEntry w
             WHERE w.userId = :userId
               AND (cast(:day as LocalDate) IS NULL OR w.loggedAt = :day)
             ORDER BY w.createdAt DESC
            """)
    List<WaterEntry> findForUser(@Param("userId") UUID userId, @Param("day") LocalDate day);

    @Query("""
            SELECT coalesce(sum(w.amountMl), 0) FROM WaterEntry w
             WHERE w.userId = :userId AND w.loggedAt = :day
            """)
    int totalMlForDay(@Param("userId") UUID userId, @Param("day") LocalDate day);
}
