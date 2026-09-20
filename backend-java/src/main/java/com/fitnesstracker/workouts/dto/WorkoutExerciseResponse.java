package com.fitnesstracker.workouts.dto;

import com.fitnesstracker.workouts.entity.WorkoutExercise;
import java.util.UUID;

public record WorkoutExerciseResponse(
        UUID id,
        ExerciseResponse exercise,
        int orderIndex,
        int sets,
        int reps,
        Double weightKg,
        String notes) {

    public static WorkoutExerciseResponse from(WorkoutExercise entry) {
        return new WorkoutExerciseResponse(
                entry.getId(),
                ExerciseResponse.from(entry.getExercise()),
                entry.getOrderIndex(),
                entry.getSets(),
                entry.getReps(),
                entry.getWeightKg(),
                entry.getNotes());
    }
}
