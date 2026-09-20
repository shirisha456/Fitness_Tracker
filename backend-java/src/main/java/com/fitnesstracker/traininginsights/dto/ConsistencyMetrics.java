package com.fitnesstracker.traininginsights.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Factual behavioural counts.
 *
 * <p>Deliberately not framed as recovery, readiness or fitness — nothing in this data
 * model supports those words.
 *
 * <p>The day-count fields carry explicit names. Jackson's snake_case strategy renders
 * {@code workoutsLast7Days} as {@code workouts_last7_days}, while the previous implementation emits
 * {@code workouts_last_7_days} — it inserts a separator before a digit run and Jackson
 * does not. The shared fixtures caught the difference; without these annotations the
 * frontend would read undefined.
 */
public record ConsistencyMetrics(
        @JsonProperty("workouts_last_7_days") int workoutsLast7Days,
        @JsonProperty("workouts_previous_7_days") int workoutsPrevious7Days,
        int change,
        int activeWeeks,
        int weeksAnalyzed,
        double averageWorkoutsPerWeek) {}
