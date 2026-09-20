package com.fitnesstracker.traininginsights.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Weighted training volume only.
 *
 * <p>Sessions without a logged weight contribute nothing — there is no defensible way to
 * add a bodyweight plank to a barbell squat — and volume alone is not evidence of improved
 * fitness. {@code changePercent} is null when the previous window is zero, never infinity.
 *
 * <p>The volume field names are pinned for the same reason as
 * {@link ConsistencyMetrics}: Jackson would render {@code current7DayVolumeKg} as
 * {@code current7_day_volume_kg}, not the contract's {@code current_7_day_volume_kg}.
 */
public record VolumeTrend(
        @JsonProperty("current_7_day_volume_kg") double current7DayVolumeKg,
        @JsonProperty("previous_7_day_volume_kg") double previous7DayVolumeKg,
        Double changePercent) {}
