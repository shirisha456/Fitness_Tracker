package com.fitnesstracker.traininginsights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code workout_exercises} row flattened with its parent workout's identity.
 *
 * <p>The engine's only input. {@code hasMedicalNote} is a boolean the database layer
 * derives from the free-text notes — the note text itself never enters the analytics
 * layer, so it cannot reach a log, an error message or the AI prompt.
 */
public record LoggedSet(
        UUID workoutId,
        LocalDate performedAt,
        Instant workoutCreatedAt,
        int sets,
        int reps,
        Double weightKg,
        boolean hasMedicalNote) {

    public LoggedSet(UUID workoutId, LocalDate performedAt, Instant workoutCreatedAt,
                     int sets, int reps, Double weightKg) {
        this(workoutId, performedAt, workoutCreatedAt, sets, reps, weightKg, false);
    }
}
