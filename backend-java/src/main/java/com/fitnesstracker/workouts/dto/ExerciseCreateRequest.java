package com.fitnesstracker.workouts.dto;

import com.fitnesstracker.workouts.entity.ExerciseCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code category} defaults to {@code other}, as the Python schema does. */
public record ExerciseCreateRequest(
        @NotBlank @Size(min = 1, max = 255) String name, ExerciseCategory category) {

    public ExerciseCategory categoryOrDefault() {
        return category == null ? ExerciseCategory.OTHER : category;
    }
}
