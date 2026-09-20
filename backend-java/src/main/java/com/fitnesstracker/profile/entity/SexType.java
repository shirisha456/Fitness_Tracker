package com.fitnesstracker.profile.entity;

import com.fitnesstracker.common.persistence.PgEnumUserType;

/** Binds {@link Sex} to the PostgreSQL {@code profile_sex} enum type. */
public class SexType extends PgEnumUserType<Sex> {

    public SexType() {
        super(Sex.class, Sex::fromValue);
    }
}
