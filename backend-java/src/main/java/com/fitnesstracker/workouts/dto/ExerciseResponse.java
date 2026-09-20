package com.fitnesstracker.workouts.dto;

import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import java.util.UUID;

public record ExerciseResponse(
        UUID id, String name, ExerciseCategory category, String muscleGroup, String equipment) {

    public static ExerciseResponse from(Exercise exercise) {
        return new ExerciseResponse(
                exercise.getId(),
                exercise.getName(),
                exercise.getCategory(),
                exercise.getMuscleGroup(),
                exercise.getEquipment());
    }
}
