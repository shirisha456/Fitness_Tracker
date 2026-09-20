package com.fitnesstracker.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateMealRequest(
        @NotBlank @Size(min = 1, max = 50) String mealType,
        @Size(max = 200) String dietaryRestrictions,
        @Min(0) @Max(3000) Integer targetCalories) {}
