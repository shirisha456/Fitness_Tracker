package com.fitnesstracker.nutrition.dto;

/** Intermediate aggregate from the daily-totals query; not part of the API surface. */
public record MealTotals(long calories, double proteinG, double carbsG, double fatG) {}
