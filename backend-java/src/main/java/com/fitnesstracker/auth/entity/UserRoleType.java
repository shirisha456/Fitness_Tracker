package com.fitnesstracker.auth.entity;

import com.fitnesstracker.common.persistence.PgEnumUserType;

/** Binds {@link UserRole} to the PostgreSQL {@code user_role} enum type. */
public class UserRoleType extends PgEnumUserType<UserRole> {

    public UserRoleType() {
        super(UserRole.class, UserRole::fromValue);
    }
}
