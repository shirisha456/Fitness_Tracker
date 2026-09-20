package com.fitnesstracker.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The model-facing shape: it knows exercise names, never our UUIDs.
 *
 * <p>Validated before use — JSON mode constrains the model to <em>parseable</em> JSON, not
 * to this schema, and a response truncated at {@code max_tokens} is not even parseable.
 */
public record LlmWorkout(@NotBlank String name, @NotNull List<LlmExercise> exercises) {

    public record LlmExercise(
            @NotBlank String exerciseName,
            @NotNull Integer sets,
            @NotNull Integer reps,
            String notes) {}
}
