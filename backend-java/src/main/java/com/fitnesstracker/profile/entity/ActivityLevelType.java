package com.fitnesstracker.profile.entity;

import com.fitnesstracker.common.persistence.PgEnumUserType;

/** Binds {@link ActivityLevel} to the {@code profile_activity_level} enum type. */
public class ActivityLevelType extends PgEnumUserType<ActivityLevel> {

    public ActivityLevelType() {
        super(ActivityLevel.class, ActivityLevel::fromValue);
    }
}
