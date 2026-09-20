package com.fitnesstracker.nutrition.dto;

import java.time.LocalDate;

/**
 * One day's totals.
 *
 * <p>Calories and millilitres are integers, macros are floats — matching the documented
 * response, where an empty day returns {@code 0} and {@code 0.0} respectively rather than
 * null.
 */
public record DailyNutritionSummary(
        LocalDate date,
        int totalCalories,
        double totalProteinG,
        double totalCarbsG,
        double totalFatG,
        int totalWaterMl) {}
