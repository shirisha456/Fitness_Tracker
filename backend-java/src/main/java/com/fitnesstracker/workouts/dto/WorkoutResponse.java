package com.fitnesstracker.workouts.dto;

import com.fitnesstracker.workouts.entity.Workout;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The detail shape, with the exercise nested — distinct from the list summary. */
public record WorkoutResponse(
        UUID id,
        String name,
        LocalDate performedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        List<WorkoutExerciseResponse> exercises) {

    public static WorkoutResponse from(Workout workout) {
        return new WorkoutResponse(
                workout.getId(),
                workout.getName(),
                workout.getPerformedAt(),
                workout.getNotes(),
                workout.getCreatedAt(),
                workout.getUpdatedAt(),
                workout.getExercises().stream().map(WorkoutExerciseResponse::from).toList());
    }
}
