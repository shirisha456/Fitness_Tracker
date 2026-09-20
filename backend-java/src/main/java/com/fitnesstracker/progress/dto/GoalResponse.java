package com.fitnesstracker.progress.dto;

import com.fitnesstracker.progress.entity.Goal;
import com.fitnesstracker.progress.entity.GoalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record GoalResponse(
        UUID id,
        String title,
        Double targetWeightKg,
        LocalDate targetDate,
        GoalStatus status,
        Instant createdAt) {

    public static GoalResponse from(Goal goal) {
        return new GoalResponse(
                goal.getId(), goal.getTitle(), goal.getTargetWeightKg(), goal.getTargetDate(),
                goal.getStatus(), goal.getCreatedAt());
    }
}
