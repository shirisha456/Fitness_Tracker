package com.fitnesstracker.ai.dto;

import java.util.UUID;

/**
 * One suggested exercise.
 *
 * <p>{@code exerciseId} is null when the model named something outside the library — the
 * service resolves names to ids and does not invent one.
 */
public record GeneratedExercise(
        String exerciseName, UUID exerciseId, int sets, int reps, String notes) {}
