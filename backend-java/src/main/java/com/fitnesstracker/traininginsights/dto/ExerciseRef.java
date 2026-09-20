package com.fitnesstracker.traininginsights.dto;

import com.fitnesstracker.workouts.entity.ExerciseCategory;
import java.util.UUID;

/** The exercise identity carried on every insight. Deliberately not the full library row. */
public record ExerciseRef(UUID id, String name, ExerciseCategory category) {}
