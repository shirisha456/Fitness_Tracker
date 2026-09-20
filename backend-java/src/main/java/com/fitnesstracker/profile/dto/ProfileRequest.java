package com.fitnesstracker.profile.dto;

import com.fitnesstracker.profile.entity.ActivityLevel;
import com.fitnesstracker.profile.entity.Sex;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Every field optional; height bounded {@code > 0} to 300, as in the documented schema. */
public record ProfileRequest(
        @Size(max = 100) String displayName,
        LocalDate dateOfBirth,
        Sex sex,
        @Positive @DecimalMax("300") Double heightCm,
        @Size(max = 255) String fitnessGoal,
        ActivityLevel activityLevel) {}
