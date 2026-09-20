package com.fitnesstracker.profile.dto;

import com.fitnesstracker.profile.entity.ActivityLevel;
import com.fitnesstracker.profile.entity.Profile;
import com.fitnesstracker.profile.entity.Sex;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProfileResponse(
        UUID id,
        String displayName,
        LocalDate dateOfBirth,
        Sex sex,
        Double heightCm,
        String fitnessGoal,
        ActivityLevel activityLevel,
        Instant createdAt,
        Instant updatedAt) {

    public static ProfileResponse from(Profile profile) {
        return new ProfileResponse(
                profile.getId(), profile.getDisplayName(), profile.getDateOfBirth(),
                profile.getSex(), profile.getHeightCm(), profile.getFitnessGoal(),
                profile.getActivityLevel(), profile.getCreatedAt(), profile.getUpdatedAt());
    }
}
