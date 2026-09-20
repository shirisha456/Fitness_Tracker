package com.fitnesstracker.common.persistence;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Function;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Binds a Java enum to a native PostgreSQL {@code CREATE TYPE ... AS ENUM} column.
 *
 * <p>Hibernate's built-in {@code SqlTypes.NAMED_ENUM} binds {@link Enum#name()}, which is
 * uppercase by Java convention. This schema's enum labels are lowercase, so NAMED_ENUM
 * produces {@code invalid input value for enum user_role: "ADMIN"} on every insert —
 * and, because the schema validator only compares column types, {@code ddl-auto=validate}
 * passes anyway. That combination (silent at startup, broken at runtime) is why this
 * mapping was measured in Phase 0.5 rather than assumed.
 *
 * <p>This type takes over both directions explicitly: it writes {@link PgEnum#getValue()}
 * as {@link Types#OTHER} so PostgreSQL coerces the untyped literal into the enum, and
 * reads the label back through the supplied lookup. {@code OTHER} also makes the value
 * usable as a query parameter, which a plain varchar binding is not — PostgreSQL will not
 * compare {@code user_role = character varying}.
 *
 * @param <E> the enum type, which must expose its database label via {@link PgEnum}
 */
public abstract class PgEnumUserType<E extends Enum<E> & PgEnum> implements UserType<E> {

    private final Class<E> enumClass;
    private final Function<String, E> fromValue;

    protected PgEnumUserType(Class<E> enumClass, Function<String, E> fromValue) {
        this.enumClass = enumClass;
        this.fromValue = fromValue;
    }

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<E> returnedClass() {
        return enumClass;
    }

    @Override
    public boolean equals(E x, E y) {
        return x == y;
    }

    @Override
    public int hashCode(E x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public E nullSafeGet(
            ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        String label = rs.getString(position);
        return label == null ? null : fromValue.apply(label);
    }

    @Override
    public void nullSafeSet(
            PreparedStatement st, E value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            // Types.OTHER sends the label untyped; PostgreSQL resolves it against the
            // target column's enum type.
            st.setObject(index, value.getValue(), Types.OTHER);
        }
    }

    @Override
    public E deepCopy(E value) {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(E value) {
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E assemble(Serializable cached, Object owner) {
        return (E) cached;
    }
}
