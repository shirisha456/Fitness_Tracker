package com.fitnesstracker.profile.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fitnesstracker.common.persistence.PgEnum;

/** Mirrors the PostgreSQL native enum {@code profile_sex}. */
public enum Sex implements PgEnum {
    MALE("male"),
    FEMALE("female"),
    UNSPECIFIED("unspecified");

    private final String value;

    Sex(String value) {
        this.value = value;
    }

    @JsonValue
    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static Sex fromValue(String value) {
        for (Sex sex : values()) {
            if (sex.value.equals(value)) {
                return sex;
            }
        }
        throw new IllegalArgumentException("Unknown profile_sex: " + value);
    }
}
