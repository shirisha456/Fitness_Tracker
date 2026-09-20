package com.fitnesstracker.progress.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Create shape. {@code status} is not settable on create — it defaults to active. */
public record GoalRequest(
        @NotBlank @Size(min = 1, max = 255) String title,
        @Positive @DecimalMax("500") Double targetWeightKg,
        LocalDate targetDate) {}
