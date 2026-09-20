package com.fitnesstracker.nutrition.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** {@code amount_ml} is 1–5000, as the documented schema constrains it. */
public record WaterEntryRequest(
        @NotNull LocalDate loggedAt, @NotNull @Min(1) @Max(5000) Integer amountMl) {}
