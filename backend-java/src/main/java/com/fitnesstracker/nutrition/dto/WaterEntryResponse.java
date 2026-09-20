package com.fitnesstracker.nutrition.dto;

import com.fitnesstracker.nutrition.entity.WaterEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WaterEntryResponse(UUID id, LocalDate loggedAt, int amountMl, Instant createdAt) {

    public static WaterEntryResponse from(WaterEntry entry) {
        return new WaterEntryResponse(
                entry.getId(), entry.getLoggedAt(), entry.getAmountMl(), entry.getCreatedAt());
    }
}
