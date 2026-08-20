"""Workout domain ORM models."""

from __future__ import annotations

import enum
import uuid
from datetime import date, datetime

from sqlalchemy import (
    Date,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.database import Base


class ExerciseCategory(enum.StrEnum):
    STRENGTH = "strength"
    CARDIO = "cardio"
    MOBILITY = "mobility"
    OTHER = "other"


class Exercise(Base):
    """Curated, seeded exercise library entry. Not user-owned."""

    __tablename__ = "exercises"
    __table_args__ = (UniqueConstraint("name"),)

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
        server_default=text("gen_random_uuid()"),
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    category: Mapped[ExerciseCategory] = mapped_column(
        Enum(
            ExerciseCategory,
            name="exercise_category",
            values_callable=lambda x: [e.value for e in x],
        ),
        nullable=False,
    )
    muscle_group: Mapped[str | None] = mapped_column(String(100), nullable=True)
    equipment: Mapped[str | None] = mapped_column(String(100), nullable=True)
    # NULL = curated library entry (seeded by migrations). Non-NULL = a custom exercise
    # one user added. The distinction matters because exercise names are free text that
    # gets concatenated into the AI system prompt — see ai/service.generate_workout.
    created_by_user_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
        index=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )


class Workout(Base):
    __tablename__ = "workouts"
    # Every list query is WHERE user_id = ? AND performed_at BETWEEN ? AND ?
    # ORDER BY performed_at DESC. One composite serves both the filter and the sort;
    # a btree scans backwards, so no DESC index is needed. This subsumes the old
    # standalone user_id index, which migration 008 drops.
    __table_args__ = (Index("ix_workouts_user_performed", "user_id", "performed_at"),)

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
        server_default=text("gen_random_uuid()"),
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    performed_at: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    notes: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )

    exercises: Mapped[list[WorkoutExercise]] = relationship(
        back_populates="workout",
        cascade="all, delete-orphan",
        order_by="WorkoutExercise.order_index",
    )


class WorkoutExercise(Base):
    __tablename__ = "workout_exercises"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
        server_default=text("gen_random_uuid()"),
    )
    workout_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("workouts.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    exercise_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("exercises.id"),
        nullable=False,
    )
    order_index: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    sets: Mapped[int] = mapped_column(Integer, nullable=False)
    reps: Mapped[int] = mapped_column(Integer, nullable=False)
    weight_kg: Mapped[float | None] = mapped_column(Float, nullable=True)
    notes: Mapped[str | None] = mapped_column(String(500), nullable=True)

    workout: Mapped[Workout] = relationship(back_populates="exercises")
    exercise: Mapped[Exercise] = relationship()
