package com.fitnesstracker.progress.entity;

import com.fitnesstracker.common.persistence.PgEnumUserType;

/** Binds {@link GoalStatus} to the PostgreSQL {@code goal_status} enum type. */
public class GoalStatusType extends PgEnumUserType<GoalStatus> {

    public GoalStatusType() {
        super(GoalStatus.class, GoalStatus::fromValue);
    }
}
