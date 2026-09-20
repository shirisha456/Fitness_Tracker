package com.fitnesstracker.progress.repository;

import com.fitnesstracker.progress.entity.BodyMeasurement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BodyMeasurementRepository extends JpaRepository<BodyMeasurement, UUID> {

    /** Backs the upsert: one row per user per day. */
    Optional<BodyMeasurement> findByUserIdAndRecordedAt(UUID userId, LocalDate recordedAt);

    @Query("""
            SELECT m FROM BodyMeasurement m
             WHERE m.userId = :userId
               AND (cast(:dateFrom as LocalDate) IS NULL OR m.recordedAt >= :dateFrom)
               AND (cast(:dateTo   as LocalDate) IS NULL OR m.recordedAt <= :dateTo)
             ORDER BY m.recordedAt DESC
            """)
    List<BodyMeasurement> findForUser(
            @Param("userId") UUID userId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo);
}
