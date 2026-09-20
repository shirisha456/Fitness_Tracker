package com.fitnesstracker.traininginsights.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One logged workout reduced to a single exercise's numbers.
 *
 * <p>A workout may contain the same exercise more than once — a drop set logged as two
 * rows, or a warm-up recorded separately — and those rows are aggregated into one session.
 *
 * @param repsPerSet set only when every row used the same rep count, so the UI can show
 *     "3 × 8" rather than a less useful "24 reps"
 * @param volumeKg {@code sets × reps × weight} summed over the rows that carry a weight;
 *     null when no row in the session was weighted
 */
public record SessionMetrics(
        UUID workoutId,
        LocalDate performedAt,
        int totalSets,
        int totalReps,
        Integer repsPerSet,
        Double topWeightKg,
        Double volumeKg) {}
