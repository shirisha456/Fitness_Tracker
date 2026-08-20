"""Workout business logic: exercise library and workout CRUD with ownership checks."""

from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy import func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.exceptions import AppException
from app.modules.auth.models import User
from app.modules.workouts.models import Exercise, ExerciseCategory, Workout, WorkoutExercise
from app.modules.workouts.schemas import (
    ExerciseCreate,
    ExerciseResponse,
    WorkoutCreate,
    WorkoutExerciseCreate,
    WorkoutExerciseResponse,
    WorkoutResponse,
    WorkoutSummary,
    WorkoutUpdate,
)


async def list_exercises(
    db: AsyncSession, category: ExerciseCategory | None = None
) -> list[Exercise]:
    stmt = select(Exercise).order_by(Exercise.name)
    if category is not None:
        stmt = stmt.where(Exercise.category == category)
    result = await db.scalars(stmt)
    return list(result.all())


async def list_exercises_for_prompt(db: AsyncSession, user: User) -> list[Exercise]:
    """Exercises safe to place in this user's AI prompt: the curated library + their own.

    Exercise names are free text that any authenticated user can write via
    POST /exercises, and generate_workout concatenates them verbatim into the system
    prompt. Scoping to `created_by_user_id IS NULL OR = me` means a name another user
    invented can never reach this user's prompt. The exercise *picker* is deliberately
    left unscoped — whether custom exercises are shared is a product decision, not a
    security one.
    """
    stmt = (
        select(Exercise)
        .where(
            or_(
                Exercise.created_by_user_id.is_(None),
                Exercise.created_by_user_id == user.id,
            )
        )
        .order_by(Exercise.name)
    )
    result = await db.scalars(stmt)
    return list(result.all())


async def get_or_create_exercise(
    db: AsyncSession, user: User, payload: ExerciseCreate
) -> Exercise:
    """Case-insensitive get-or-create against the shared exercise library."""
    name = payload.name.strip()
    existing = await db.scalar(select(Exercise).where(func.lower(Exercise.name) == name.lower()))
    if existing is not None:
        return existing

    exercise = Exercise(name=name, category=payload.category, created_by_user_id=user.id)
    try:
        # SAVEPOINT: losing the race must undo only this INSERT. The previous
        # `await db.rollback()` discarded the caller's entire request transaction
        # to recover from a duplicate name.
        async with db.begin_nested():
            db.add(exercise)
            await db.flush()
    except IntegrityError:
        existing = await db.scalar(
            select(Exercise).where(func.lower(Exercise.name) == name.lower())
        )
        if existing is None:
            raise
        return existing
    return exercise


async def _validate_exercise_ids(
    db: AsyncSession, items: list[WorkoutExerciseCreate]
) -> None:
    requested_ids = {item.exercise_id for item in items}
    if not requested_ids:
        return
    result = await db.scalars(select(Exercise.id).where(Exercise.id.in_(requested_ids)))
    existing_ids = set(result.all())
    missing = requested_ids - existing_ids
    if missing:
        raise AppException(
            code="BAD_REQUEST",
            message=f"Unknown exercise id(s): {', '.join(str(i) for i in missing)}",
            status_code=400,
        )


def _apply_exercises(workout: Workout, items: list[WorkoutExerciseCreate]) -> None:
    for index, item in enumerate(items):
        workout.exercises.append(
            WorkoutExercise(
                exercise_id=item.exercise_id,
                order_index=index,
                sets=item.sets,
                reps=item.reps,
                weight_kg=item.weight_kg,
                notes=item.notes,
            )
        )


async def _load_workout(db: AsyncSession, workout_id: uuid.UUID) -> Workout | None:
    stmt = (
        select(Workout)
        .where(Workout.id == workout_id)
        .options(selectinload(Workout.exercises).selectinload(WorkoutExercise.exercise))
    )
    return await db.scalar(stmt)


async def create_workout(db: AsyncSession, user: User, payload: WorkoutCreate) -> Workout:
    await _validate_exercise_ids(db, payload.exercises)

    workout = Workout(
        user_id=user.id,
        name=payload.name,
        performed_at=payload.performed_at,
        notes=payload.notes,
    )
    _apply_exercises(workout, payload.exercises)
    db.add(workout)
    await db.flush()

    loaded = await _load_workout(db, workout.id)
    assert loaded is not None
    return loaded


async def list_workouts(
    db: AsyncSession,
    user: User,
    *,
    date_from: date | None = None,
    date_to: date | None = None,
) -> list[WorkoutSummary]:
    stmt = (
        select(Workout)
        .where(Workout.user_id == user.id)
        .options(selectinload(Workout.exercises))
        .order_by(Workout.performed_at.desc(), Workout.created_at.desc())
    )
    if date_from is not None:
        stmt = stmt.where(Workout.performed_at >= date_from)
    if date_to is not None:
        stmt = stmt.where(Workout.performed_at <= date_to)

    result = await db.scalars(stmt)
    return [
        WorkoutSummary(
            id=w.id,
            name=w.name,
            performed_at=w.performed_at,
            exercise_count=len(w.exercises),
        )
        for w in result.all()
    ]


async def get_workout(db: AsyncSession, user: User, workout_id: uuid.UUID) -> Workout:
    workout = await _load_workout(db, workout_id)
    if workout is None or workout.user_id != user.id:
        raise AppException(code="NOT_FOUND", message="Workout not found", status_code=404)
    return workout


async def update_workout(
    db: AsyncSession,
    user: User,
    workout_id: uuid.UUID,
    payload: WorkoutUpdate,
) -> Workout:
    workout = await get_workout(db, user, workout_id)
    await _validate_exercise_ids(db, payload.exercises)

    workout.name = payload.name
    workout.performed_at = payload.performed_at
    workout.notes = payload.notes

    workout.exercises.clear()
    await db.flush()
    _apply_exercises(workout, payload.exercises)
    await db.flush()

    loaded = await _load_workout(db, workout.id)
    assert loaded is not None
    return loaded


async def delete_workout(db: AsyncSession, user: User, workout_id: uuid.UUID) -> None:
    workout = await get_workout(db, user, workout_id)
    await db.delete(workout)
    await db.flush()


def to_exercise_response(exercise: Exercise) -> ExerciseResponse:
    return ExerciseResponse.model_validate(exercise)


def to_workout_response(workout: Workout) -> WorkoutResponse:
    return WorkoutResponse(
        id=workout.id,
        name=workout.name,
        performed_at=workout.performed_at,
        notes=workout.notes,
        created_at=workout.created_at,
        updated_at=workout.updated_at,
        exercises=[
            WorkoutExerciseResponse.model_validate(we) for we in workout.exercises
        ],
    )
