package com.fitnesstracker.workouts.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The list shape. Deliberately not the detail shape: {@code GET /workouts} returns
 * {@code exercise_count}, and the count is computed in SQL rather than by loading each
 * workout's children to call {@code size()}.
 */
public record WorkoutSummaryResponse(
        UUID id, String name, LocalDate performedAt, long exerciseCount) {}
