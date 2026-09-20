package com.fitnesstracker.nutrition.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Bounds copied from the documented schema: calories 0–20000, macros ≥ 0 or absent. */
public record MealRequest(
        @NotBlank @Size(min = 1, max = 255) String name,
        @NotNull LocalDate loggedAt,
        @NotNull @Min(0) @Max(20000) Integer calories,
        @PositiveOrZero Double proteinG,
        @PositiveOrZero Double carbsG,
        @PositiveOrZero Double fatG,
        @Size(max = 500) String notes) {}
