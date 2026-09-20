package com.fitnesstracker.traininginsights.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The complete, closed set of next-session suggestions. */
public enum SuggestionKind {
    MAINTAIN("maintain"),
    SMALL_PROGRESSION("small_progression"),
    REVIEW_EXERCISE("review_exercise"),
    INSUFFICIENT_HISTORY("insufficient_history"),
    CONSULT_PROFESSIONAL("consult_professional");

    private final String value;

    SuggestionKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SuggestionKind fromValue(String value) {
        for (SuggestionKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown suggestion kind: " + value);
    }
}
