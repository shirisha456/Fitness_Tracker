package com.fitnesstracker.profile.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fitnesstracker.common.persistence.PgEnum;

/** Mirrors the PostgreSQL native enum {@code profile_activity_level}. */
public enum ActivityLevel implements PgEnum {
    SEDENTARY("sedentary"),
    LIGHT("light"),
    MODERATE("moderate"),
    ACTIVE("active"),
    VERY_ACTIVE("very_active");

    private final String value;

    ActivityLevel(String value) {
        this.value = value;
    }

    @JsonValue
    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ActivityLevel fromValue(String value) {
        for (ActivityLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown profile_activity_level: " + value);
    }
}
