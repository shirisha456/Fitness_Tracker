package com.fitnesstracker.traininginsights.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The complete, closed set of trend verdicts.
 *
 * <p>Six, not four. {@code NOT_APPLICABLE} exists because sets and reps cannot express
 * distance or duration, so claiming a trend for a 5 km run logged as {@code 1 x 1} would
 * be fabricated; {@code DECLINING} exists because reporting a 15% load drop as "stable"
 * would be wrong. Both are implemented in the previous implementation and must be preserved.
 */
public enum TrendClassification {
    PROGRESSING("progressing"),
    STABLE("stable"),
    POSSIBLE_PLATEAU("possible_plateau"),
    DECLINING("declining"),
    INSUFFICIENT_DATA("insufficient_data"),
    NOT_APPLICABLE("not_applicable");

    private final String value;

    TrendClassification(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TrendClassification fromValue(String value) {
        for (TrendClassification classification : values()) {
            if (classification.value.equals(value)) {
                return classification;
            }
        }
        throw new IllegalArgumentException("Unknown classification: " + value);
    }
}
