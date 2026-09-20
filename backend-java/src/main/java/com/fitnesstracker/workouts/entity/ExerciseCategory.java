package com.fitnesstracker.workouts.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Mirrors the PostgreSQL native enum {@code exercise_category}. */

public enum ExerciseCategory implements com.fitnesstracker.common.persistence.PgEnum {
    STRENGTH("strength"),
    CARDIO("cardio"),
    MOBILITY("mobility"),
    OTHER("other");

    private final String value;

    ExerciseCategory(String value) {
        this.value = value;
    }

    /** The lowercase label used in JSON and stored in PostgreSQL. */
    @JsonValue
    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ExerciseCategory fromValue(String value) {
        for (ExerciseCategory category : values()) {
            if (category.value.equals(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown exercise_category: " + value);
    }
}
