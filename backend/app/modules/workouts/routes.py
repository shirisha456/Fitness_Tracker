"""Workout & exercise HTTP routes."""

from __future__ import annotations

import uuid
from datetime import date

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.dependencies import get_current_user, get_db
from app.modules.auth.models import User
from app.modules.workouts import service as workouts_service
from app.modules.workouts.models import ExerciseCategory
from app.modules.workouts.schemas import ExerciseCreate, WorkoutCreate, WorkoutUpdate

exercises_router = APIRouter(prefix="/exercises", tags=["exercises"])
workouts_router = APIRouter(prefix="/workouts", tags=["workouts"])


@exercises_router.get("")
async def list_exercises(
    category: ExerciseCategory | None = Query(default=None),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    exercises = await workouts_service.list_exercises(db, category)
    return {"data": [workouts_service.to_exercise_response(e) for e in exercises]}


@exercises_router.post("", status_code=201)
async def create_exercise(
    payload: ExerciseCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    exercise = await workouts_service.get_or_create_exercise(db, user, payload)
    return {"data": workouts_service.to_exercise_response(exercise)}


@workouts_router.post("", status_code=201)
async def create_workout(
    payload: WorkoutCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    workout = await workouts_service.create_workout(db, user, payload)
    return {"data": workouts_service.to_workout_response(workout)}


@workouts_router.get("")
async def list_workouts(
    date_from: date | None = Query(default=None),
    date_to: date | None = Query(default=None),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    summaries = await workouts_service.list_workouts(
        db, user, date_from=date_from, date_to=date_to
    )
    return {"data": summaries}


@workouts_router.get("/{workout_id}")
async def get_workout(
    workout_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    workout = await workouts_service.get_workout(db, user, workout_id)
    return {"data": workouts_service.to_workout_response(workout)}


@workouts_router.put("/{workout_id}")
async def update_workout(
    workout_id: uuid.UUID,
    payload: WorkoutUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    workout = await workouts_service.update_workout(db, user, workout_id, payload)
    return {"data": workouts_service.to_workout_response(workout)}


@workouts_router.delete("/{workout_id}", status_code=204, response_model=None)
async def delete_workout(
    workout_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    await workouts_service.delete_workout(db, user, workout_id)
