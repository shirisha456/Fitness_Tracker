package com.fitnesstracker.progress.dto;

import com.fitnesstracker.progress.entity.GoalStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Update adds {@code status}, defaulting to active when omitted — as the documented schema does. */
public record GoalUpdateRequest(
        @NotBlank @Size(min = 1, max = 255) String title,
        @Positive @DecimalMax("500") Double targetWeightKg,
        LocalDate targetDate,
        GoalStatus status) {

    public GoalStatus statusOrDefault() {
        return status == null ? GoalStatus.ACTIVE : status;
    }
}
