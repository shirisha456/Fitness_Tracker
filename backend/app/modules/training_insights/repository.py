"""The only place training-insight analytics touch the database.

Three queries cover the whole feature, and none of them is issued per exercise —
the overview joins every exercise at once and groups in Python, so adding a
seventh exercise to a user's routine does not add a seventh round trip.

Ownership is enforced inside the SQL: every statement filters on
`workouts.user_id`. A caller passing another user's `exercise_id` gets an empty
result, never someone else's history.

Index coverage (no new index is introduced by this feature):
  `ix_workouts_user_performed (user_id, performed_at)` drives the outer filter and
  ordering, and `ix_workout_exercises_workout_id` serves the join. Driving from
  `workout_exercises.exercise_id` instead would be far less selective than the
  per-user filter, so an index on that column would not be used here.
"""

from __future__ import annotations

import uuid
from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.modules.auth.models import User
from app.modules.training_insights.analytics import LoggedSet
from app.modules.training_insights.rules import mentions_medical_concern
from app.modules.training_insights.schemas import ExerciseRef
from app.modules.workouts.models import Exercise, Workout, WorkoutExercise


def _to_logged_set(
    workout_id: uuid.UUID,
    performed_at: date,
    created_at,
    sets: int,
    reps: int,
    weight_kg: float | None,
    exercise_notes: str | None,
    workout_notes: str | None,
) -> LoggedSet:
    return LoggedSet(
        workout_id=workout_id,
        performed_at=performed_at,
        workout_created_at=created_at,
        sets=sets,
        reps=reps,
        weight_kg=weight_kg,
        # Only the boolean crosses this boundary. The note text stays here, so it
        # cannot reach the analytics layer, a log line, an error message or a prompt.
        has_medical_note=mentions_medical_concern(exercise_notes, workout_notes),
    )


async def get_exercise(db: AsyncSession, exercise_id: uuid.UUID) -> Exercise | None:
    """Exercises are a shared library, so this needs no ownership filter — but the
    history queries below still scope every row to the calling user."""
    return await db.get(Exercise, exercise_id)


async def fetch_exercise_history(
    db: AsyncSession,
    user: User,
    exercise_id: uuid.UUID,
    *,
    today: date,
    lookback_days: int,
) -> list[LoggedSet]:
    """Every logged set of one exercise for one user, within the lookback window.

    Future-dated workouts are excluded: the schema has no planned/completed flag,
    so a workout dated after today cannot be treated as performed.
    """
    date_from = today - timedelta(days=lookback_days)
    stmt = (
        select(
            Workout.id,
            Workout.performed_at,
            Workout.created_at,
            WorkoutExercise.sets,
            WorkoutExercise.reps,
            WorkoutExercise.weight_kg,
            WorkoutExercise.notes,
            Workout.notes,
        )
        .join(WorkoutExercise, WorkoutExercise.workout_id == Workout.id)
        .where(
            Workout.user_id == user.id,
            WorkoutExercise.exercise_id == exercise_id,
            Workout.performed_at >= date_from,
            Workout.performed_at <= today,
        )
        .order_by(Workout.performed_at, Workout.created_at)
    )
    rows = await db.execute(stmt)
    return [_to_logged_set(*row) for row in rows.all()]


async def fetch_recent_history_by_exercise(
    db: AsyncSession,
    user: User,
    *,
    today: date,
    lookback_days: int,
) -> tuple[dict[uuid.UUID, ExerciseRef], dict[uuid.UUID, list[LoggedSet]]]:
    """One query for the whole overview: every exercise the user trained recently.

    Returns the exercise identities and their logged sets keyed by exercise id.
    Grouping happens in Python rather than in SQL because the analytics engine
    needs the individual rows anyway, and one pass over a bounded result set is
    cheaper than a second round trip per exercise.
    """
    date_from = today - timedelta(days=lookback_days)
    stmt = (
        select(
            Exercise.id,
            Exercise.name,
            Exercise.category,
            Workout.id,
            Workout.performed_at,
            Workout.created_at,
            WorkoutExercise.sets,
            WorkoutExercise.reps,
            WorkoutExercise.weight_kg,
            WorkoutExercise.notes,
            Workout.notes,
        )
        .join(WorkoutExercise, WorkoutExercise.workout_id == Workout.id)
        .join(Exercise, Exercise.id == WorkoutExercise.exercise_id)
        .where(
            Workout.user_id == user.id,
            Workout.performed_at >= date_from,
            Workout.performed_at <= today,
        )
        .order_by(Workout.performed_at, Workout.created_at)
    )
    rows = await db.execute(stmt)

    refs: dict[uuid.UUID, ExerciseRef] = {}
    history: dict[uuid.UUID, list[LoggedSet]] = {}
    for exercise_id, name, category, *rest in rows.all():
        refs.setdefault(
            exercise_id, ExerciseRef(id=exercise_id, name=name, category=category)
        )
        history.setdefault(exercise_id, []).append(_to_logged_set(*rest))
    return refs, history


async def fetch_workout_dates(
    db: AsyncSession,
    user: User,
    *,
    today: date,
    lookback_days: int,
) -> list[date]:
    """Dates of the user's workouts, for the consistency counts.

    Queried separately from the exercise join because a workout logged with no
    exercises still counts as a session the user showed up for, and an inner join
    would silently drop it.
    """
    date_from = today - timedelta(days=lookback_days)
    stmt = (
        select(Workout.performed_at)
        .where(
            Workout.user_id == user.id,
            Workout.performed_at >= date_from,
            Workout.performed_at <= today,
        )
        .order_by(Workout.performed_at)
    )
    result = await db.scalars(stmt)
    return list(result.all())
