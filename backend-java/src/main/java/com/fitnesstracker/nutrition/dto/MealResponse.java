package com.fitnesstracker.nutrition.dto;

import com.fitnesstracker.nutrition.entity.Meal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MealResponse(
        UUID id,
        String name,
        LocalDate loggedAt,
        int calories,
        Double proteinG,
        Double carbsG,
        Double fatG,
        String notes,
        Instant createdAt) {

    public static MealResponse from(Meal meal) {
        return new MealResponse(
                meal.getId(), meal.getName(), meal.getLoggedAt(), meal.getCalories(),
                meal.getProteinG(), meal.getCarbsG(), meal.getFatG(), meal.getNotes(),
                meal.getCreatedAt());
    }
}
