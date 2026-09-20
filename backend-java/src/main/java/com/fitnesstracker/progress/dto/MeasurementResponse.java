package com.fitnesstracker.progress.dto;

import com.fitnesstracker.progress.entity.BodyMeasurement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MeasurementResponse(
        UUID id,
        LocalDate recordedAt,
        Double weightKg,
        Double bodyFatPct,
        Double waistCm,
        Double chestCm,
        Double hipsCm,
        Double armCm,
        String notes,
        Instant createdAt) {

    public static MeasurementResponse from(BodyMeasurement measurement) {
        return new MeasurementResponse(
                measurement.getId(), measurement.getRecordedAt(), measurement.getWeightKg(),
                measurement.getBodyFatPct(), measurement.getWaistCm(), measurement.getChestCm(),
                measurement.getHipsCm(), measurement.getArmCm(), measurement.getNotes(),
                measurement.getCreatedAt());
    }
}
