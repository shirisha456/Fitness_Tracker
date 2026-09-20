package com.fitnesstracker.ai.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record GenerateMealResponse(@NotNull List<GeneratedMeal> suggestions) {

    public record GeneratedMeal(
            @NotNull String name,
            @NotNull Integer estimatedCalories,
            @NotNull Double proteinG,
            @NotNull Double carbsG,
            @NotNull Double fatG) {}
}
