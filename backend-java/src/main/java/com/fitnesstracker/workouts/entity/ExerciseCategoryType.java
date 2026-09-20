package com.fitnesstracker.workouts.entity;

import com.fitnesstracker.common.persistence.PgEnumUserType;

/** Binds {@link ExerciseCategory} to the PostgreSQL {@code exercise_category} enum type. */
public class ExerciseCategoryType extends PgEnumUserType<ExerciseCategory> {

    public ExerciseCategoryType() {
        super(ExerciseCategory.class, ExerciseCategory::fromValue);
    }
}
