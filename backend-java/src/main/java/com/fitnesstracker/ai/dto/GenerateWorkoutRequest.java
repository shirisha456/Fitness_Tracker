package com.fitnesstracker.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bounds copied from the documented schema: duration 10–180, difficulty a closed set. */
public record GenerateWorkoutRequest(
        @NotBlank @Size(min = 1, max = 200) String goal,
        @Size(max = 200) String equipment,
        @Min(10) @Max(180) Integer durationMinutes,
        @Pattern(regexp = "beginner|intermediate|advanced") String difficulty) {

    public int durationOrDefault() {
        return durationMinutes == null ? 45 : durationMinutes;
    }

    public String difficultyOrDefault() {
        return difficulty == null ? "intermediate" : difficulty;
    }
}
