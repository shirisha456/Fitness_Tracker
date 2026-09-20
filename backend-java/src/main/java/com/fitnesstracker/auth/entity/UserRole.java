package com.fitnesstracker.auth.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Mirrors the PostgreSQL native enum type {@code user_role ('user', 'admin')}.
 *
 * <p>The database labels are lowercase and Java constants are conventionally uppercase,
 * so the two cannot be bridged by {@link Enum#name()} alone. {@link #getValue()} carries
 * the database/JSON label; see {@code PostgresEnumType} for how it reaches the driver.
 */

public enum UserRole implements com.fitnesstracker.common.persistence.PgEnum {
    USER("user"),
    ADMIN("admin");

    private final String value;

    UserRole(String value) {
        this.value = value;
    }

    /** The lowercase label used in JSON and stored in PostgreSQL. */
    @JsonValue
    @Override
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static UserRole fromValue(String value) {
        for (UserRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown user_role: " + value);
    }
}
