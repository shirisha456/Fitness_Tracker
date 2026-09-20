package com.fitnesstracker.progress.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fitnesstracker.common.persistence.PgEnum;

/** Mirrors the PostgreSQL native enum {@code goal_status}. */
public enum GoalStatus implements PgEnum {
    ACTIVE("active"),
    ACHIEVED("achieved"),
    ABANDONED("abandoned");

    private final String value;

    GoalStatus(String value) {
        this.value = value;
    }

    @JsonValue
    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static GoalStatus fromValue(String value) {
        for (GoalStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown goal_status: " + value);
    }
}
