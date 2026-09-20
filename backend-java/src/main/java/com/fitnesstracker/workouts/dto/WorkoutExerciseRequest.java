package com.fitnesstracker.workouts.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Bounds copied from the Python schema: sets 1–50, reps 1–1000, weight ≥ 0 or absent.
 *
 * <p>{@code orderIndex} is deliberately absent — it is assigned from array position, so a
 * client cannot set it.
 */
public record WorkoutExerciseRequest(
        @NotNull UUID exerciseId,
        @NotNull @Min(1) @Max(50) Integer sets,
        @NotNull @Min(1) @Max(1000) Integer reps,
        @PositiveOrZero Double weightKg,
        @Size(max = 500) String notes) {}
