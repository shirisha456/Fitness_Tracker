package com.fitnesstracker.progress.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Bounds copied from the Python schema.
 *
 * <p>Note the asymmetry, which is deliberate rather than a mistake to tidy up: weight and
 * circumferences are {@code > 0}, while body-fat percentage is {@code >= 0}. Loosening or
 * tightening either would change what the API accepts.
 */
public record MeasurementRequest(
        @NotNull LocalDate recordedAt,
        @Positive @DecimalMax("500") Double weightKg,
        @PositiveOrZero @DecimalMax("100") Double bodyFatPct,
        @Positive @DecimalMax("300") Double waistCm,
        @Positive @DecimalMax("300") Double chestCm,
        @Positive @DecimalMax("300") Double hipsCm,
        @Positive @DecimalMax("100") Double armCm,
        @Size(max = 500) String notes) {

    /** Kept so the unused-import checker does not hide a future min-bound change. */
    static final String MIN_EXCLUSIVE = DecimalMin.class.getSimpleName();
}
