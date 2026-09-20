"""Training-insight response schemas.

These double as the analytics engine's return types: `analytics.py` builds them
directly, so there is no second parallel set of dataclasses to keep in sync.
Every classification carries the provenance needed to answer "why did the system
say this?" — `sessions_analyzed`, `date_range` and `evidence`.
"""

from __future__ import annotations

import uuid
from datetime import date

from pydantic import BaseModel, ConfigDict, Field

from app.modules.training_insights.rules import (
    MetricBasis,
    SuggestionKind,
    TrendClassification,
)
from app.modules.workouts.models import ExerciseCategory


class DateRange(BaseModel):
    from_date: date = Field(serialization_alias="from")
    to_date: date = Field(serialization_alias="to")

    model_config = ConfigDict(populate_by_name=True)


class ExerciseRef(BaseModel):
    id: uuid.UUID
    name: str
    category: ExerciseCategory


class SessionMetrics(BaseModel):
    """One logged workout, reduced to this exercise's numbers.

    A workout may contain the same exercise more than once (e.g. a drop set
    logged as two rows); those rows are aggregated into a single session here.
    """

    workout_id: uuid.UUID
    performed_at: date
    total_sets: int
    total_reps: int
    # Only set when every row in the session used the same rep count, so the UI
    # can show "3 x 8" instead of a less useful "24 reps".
    reps_per_set: int | None = None
    top_weight_kg: float | None = None
    # sets x reps x weight, summed over the rows that actually carry a weight.
    # None when no row in the session was weighted.
    volume_kg: float | None = None


class PersonalBests(BaseModel):
    """Deterministic records over the queried history. All fields are None when
    the history contains no weighted sessions."""

    heaviest_weight_kg: float | None = None
    heaviest_weight_on: date | None = None
    best_session_volume_kg: float | None = None
    best_session_volume_on: date | None = None
    most_reps_at_heaviest_weight: int | None = None
    most_reps_at_heaviest_weight_on: date | None = None


class NextSessionSuggestion(BaseModel):
    kind: SuggestionKind
    message: str
    rationale: str
    current_load_kg: float | None = None
    suggested_load_kg: float | None = None


class ExerciseInsight(BaseModel):
    exercise: ExerciseRef
    classification: TrendClassification
    metric_basis: MetricBasis
    sessions_analyzed: int
    date_range: DateRange | None = None
    current_weight_kg: float | None = None
    previous_weight_kg: float | None = None
    primary_change_percent: float | None = None
    volume_change_percent: float | None = None
    plateau_detected: bool = False
    evidence: list[str] = Field(default_factory=list)
    suggestion: NextSessionSuggestion
    # Deterministic by default; replaced by AI prose only when explicitly requested
    # and only when the AI call succeeds.
    explanation: str
    explanation_source: str = "deterministic"


class ExerciseHistory(BaseModel):
    exercise: ExerciseRef
    sessions: list[SessionMetrics]
    personal_bests: PersonalBests
    date_range: DateRange | None = None


class ConsistencyMetrics(BaseModel):
    """Factual behavioural counts. Deliberately *not* framed as recovery or
    readiness — nothing in this data model supports those claims."""

    workouts_last_7_days: int
    workouts_previous_7_days: int
    change: int
    active_weeks: int
    weeks_analyzed: int
    average_workouts_per_week: float


class VolumeTrend(BaseModel):
    """Weighted training volume only. Sessions without a logged weight contribute
    nothing here, and volume alone is not evidence of improved fitness."""

    current_7_day_volume_kg: float
    previous_7_day_volume_kg: float
    change_percent: float | None = None


class TrainingOverview(BaseModel):
    generated_on: date
    date_range: DateRange
    consistency: ConsistencyMetrics
    volume: VolumeTrend
    exercises: list[ExerciseInsight]


class TrainingRecommendation(BaseModel):
    exercise: ExerciseRef | None = None
    classification: TrendClassification | None = None
    suggestion: NextSessionSuggestion
    evidence: list[str] = Field(default_factory=list)


class TrainingRecommendations(BaseModel):
    generated_on: date
    recommendations: list[TrainingRecommendation]
