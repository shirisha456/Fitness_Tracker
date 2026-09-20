package com.fitnesstracker.workouts.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Create and update share one shape — {@code PUT} is a full replace, as in Python. */
public record WorkoutRequest(
        @NotBlank @Size(min = 1, max = 255) String name,
        @NotNull LocalDate performedAt,
        @Size(max = 2000) String notes,
        @Valid List<WorkoutExerciseRequest> exercises) {

    public List<WorkoutExerciseRequest> exercisesOrEmpty() {
        return exercises == null ? List.of() : exercises;
    }
}
