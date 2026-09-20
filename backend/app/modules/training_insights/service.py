"""Orchestration for training insights.

This layer does three things and nothing else: fetch rows, hand them to the pure
engine, and — only when explicitly asked — swap the deterministic explanation for
AI prose. It never computes a metric itself, and it never lets an AI failure turn
into a failed request.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import AppException
from app.core.logging import get_logger
from app.modules.auth.models import User
from app.modules.training_insights import analytics, repository
from app.modules.training_insights.rules import (
    CONSISTENCY_TREND_WEEKS,
    HISTORY_LOOKBACK_DAYS,
    HISTORY_MAX_SESSIONS,
    OVERVIEW_LOOKBACK_DAYS,
    OVERVIEW_MAX_EXERCISES,
    SuggestionKind,
    TrendClassification,
)
from app.modules.training_insights.schemas import (
    DateRange,
    ExerciseHistory,
    ExerciseInsight,
    ExerciseRef,
    TrainingOverview,
    TrainingRecommendation,
    TrainingRecommendations,
)

logger = get_logger(__name__)


def _today():
    return datetime.now(UTC).date()


async def _resolve_exercise(db: AsyncSession, exercise_id: uuid.UUID) -> ExerciseRef:
    exercise = await repository.get_exercise(db, exercise_id)
    if exercise is None:
        raise AppException(code="NOT_FOUND", message="Exercise not found", status_code=404)
    return ExerciseRef(id=exercise.id, name=exercise.name, category=exercise.category)


async def get_exercise_insight(
    db: AsyncSession,
    user: User,
    exercise_id: uuid.UUID,
    *,
    explain: bool = False,
) -> ExerciseInsight:
    """The deterministic verdict for one exercise, optionally re-worded by the AI.

    The insight is fully computed before `explain` is even considered, so the
    analytics half of this endpoint cannot be affected by OpenAI's availability.
    """
    exercise = await _resolve_exercise(db, exercise_id)
    today = _today()
    entries = await repository.fetch_exercise_history(
        db, user, exercise_id, today=today, lookback_days=HISTORY_LOOKBACK_DAYS
    )
    insight = analytics.build_exercise_insight(exercise, entries)

    if explain:
        await _apply_ai_explanation(insight)
    return insight


async def _apply_ai_explanation(insight: ExerciseInsight) -> None:
    """Replace the deterministic prose with an AI rewording, or leave it alone.

    Deliberately swallows `AppException` from the AI module — a missing API key,
    a rate limit or an upstream outage must degrade the wording, never the
    endpoint. Only `AppException` is caught; a genuine bug still propagates.
    """
    # Imported here rather than at module scope: the AI module imports workouts and
    # nutrition services, and a top-level import would make analytics depend on the
    # AI module's import graph just to compute numbers.
    from app.modules.ai import service as ai_service

    try:
        text = await ai_service.explain_training_insight(insight)
    except AppException as exc:
        logger.info(
            "AI explanation unavailable, using deterministic text",
            extra={"code": exc.code, "status_code": exc.status_code},
        )
        return
    if text:
        insight.explanation = text
        insight.explanation_source = "ai"


async def get_exercise_history(
    db: AsyncSession,
    user: User,
    exercise_id: uuid.UUID,
    *,
    limit: int = HISTORY_MAX_SESSIONS,
) -> ExerciseHistory:
    exercise = await _resolve_exercise(db, exercise_id)
    today = _today()
    entries = await repository.fetch_exercise_history(
        db, user, exercise_id, today=today, lookback_days=HISTORY_LOOKBACK_DAYS
    )

    sessions = analytics.build_sessions(entries)
    # Personal bests are computed over the full queried history, then the table is
    # trimmed — so a record is never lost just because it fell outside the page.
    personal_bests = analytics.find_personal_bests(sessions)
    trimmed = sessions[-limit:] if limit > 0 else sessions

    date_range = (
        DateRange(from_date=trimmed[0].performed_at, to_date=trimmed[-1].performed_at)
        if trimmed
        else None
    )
    return ExerciseHistory(
        exercise=exercise,
        sessions=trimmed,
        personal_bests=personal_bests,
        date_range=date_range,
    )


async def _recent_insights(
    db: AsyncSession, user: User, today: date
) -> tuple[list[ExerciseInsight], list[analytics.LoggedSet]]:
    """Per-exercise insights over the overview window, most recently trained first.

    Returns the flattened rows alongside them so callers that also need workload
    totals do not re-query. One database round trip serves every exercise.
    """
    refs, history = await repository.fetch_recent_history_by_exercise(
        db, user, today=today, lookback_days=OVERVIEW_LOOKBACK_DAYS
    )
    insights = [
        analytics.build_exercise_insight(refs[exercise_id], entries)
        for exercise_id, entries in history.items()
    ]
    insights.sort(
        key=lambda insight: (
            max(entry.performed_at for entry in history[insight.exercise.id]),
            insight.exercise.name,
        ),
        reverse=True,
    )
    all_entries = [entry for entries in history.values() for entry in entries]
    return insights[:OVERVIEW_MAX_EXERCISES], all_entries


async def get_overview(db: AsyncSession, user: User) -> TrainingOverview:
    today = _today()
    insights, all_entries = await _recent_insights(db, user, today)
    workout_dates = await repository.fetch_workout_dates(
        db, user, today=today, lookback_days=CONSISTENCY_TREND_WEEKS * 7
    )

    return TrainingOverview(
        generated_on=today,
        date_range=DateRange(
            from_date=today - timedelta(days=OVERVIEW_LOOKBACK_DAYS),
            to_date=today,
        ),
        consistency=analytics.compute_consistency(workout_dates, today=today),
        volume=analytics.compute_volume_trend(all_entries, today=today),
        exercises=insights,
    )


# Suggestions worth surfacing unprompted. "Maintain" and "not enough history" are
# true but not actionable, so they stay on the exercise's own page.
_ACTIONABLE_SUGGESTIONS = frozenset(
    {
        SuggestionKind.SMALL_PROGRESSION,
        SuggestionKind.REVIEW_EXERCISE,
        SuggestionKind.CONSULT_PROFESSIONAL,
    }
)


async def get_recommendations(db: AsyncSession, user: User) -> TrainingRecommendations:
    """Deterministic, evidence-carrying suggestions. No LLM involved at all."""
    today = _today()
    insights, _ = await _recent_insights(db, user, today)

    recommendations = [
        TrainingRecommendation(
            exercise=insight.exercise,
            classification=insight.classification,
            suggestion=insight.suggestion,
            evidence=insight.evidence,
        )
        for insight in insights
        if insight.suggestion.kind in _ACTIONABLE_SUGGESTIONS
    ]
    return TrainingRecommendations(generated_on=today, recommendations=recommendations)


async def build_coach_signals(db: AsyncSession, user: User) -> list[str]:
    """Compact deterministic training signals for the existing AI coach prompt.

    Returns already-computed conclusions, never raw history — the coach explains
    these numbers, it does not derive them.
    """
    today = _today()
    workout_dates = await repository.fetch_workout_dates(
        db, user, today=today, lookback_days=CONSISTENCY_TREND_WEEKS * 7
    )
    consistency = analytics.compute_consistency(workout_dates, today=today)

    lines = [
        f"Training frequency: {consistency.workouts_last_7_days} sessions in the last 7 days "
        f"vs {consistency.workouts_previous_7_days} the week before",
    ]

    insights, _ = await _recent_insights(db, user, today)
    notable = [
        insight
        for insight in insights
        if insight.classification
        in (
            TrendClassification.PROGRESSING,
            TrendClassification.POSSIBLE_PLATEAU,
            TrendClassification.DECLINING,
        )
    ]
    for insight in notable[:3]:
        lines.append(f"{insight.exercise.name}: {insight.classification.value}")
    if not notable:
        lines.append("No exercise has enough comparable history for a trend verdict yet.")
    return lines
