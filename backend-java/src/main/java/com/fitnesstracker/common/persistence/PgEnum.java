package com.fitnesstracker.common.persistence;

/**
 * Implemented by every enum backed by a native PostgreSQL enum type.
 *
 * <p>The database labels are lowercase ({@code 'user'}, {@code 'strength'}) while Java
 * constants are uppercase, so {@link Enum#name()} cannot be used as the stored value.
 * Each enum carries its label explicitly and the JPA converters below use it.
 */
public interface PgEnum {

    /** The exact label stored in PostgreSQL, and emitted in JSON. */
    String getValue();
}
