package com.fitnesstracker.traininginsights.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which metric the classification was actually computed from. */
public enum MetricBasis {
    LOAD("load"),
    REPS("reps"),
    NONE("none");

    private final String value;

    MetricBasis(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static MetricBasis fromValue(String value) {
        for (MetricBasis basis : values()) {
            if (basis.value.equals(value)) {
                return basis;
            }
        }
        throw new IllegalArgumentException("Unknown metric basis: " + value);
    }
}
