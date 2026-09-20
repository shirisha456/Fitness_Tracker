"""Unit tests for the deterministic training-analytics engine.

Everything here is pure — no database, no HTTP, no clock. `today` and session
dates are always explicit so the same input always produces the same verdict.
"""

from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta

import pytest

from app.modules.training_insights import analytics, rules
from app.modules.training_insights.analytics import LoggedSet
from app.modules.training_insights.rules import (
    MetricBasis,
    SuggestionKind,
    TrendClassification,
)
from app.modules.training_insights.schemas import ExerciseRef
from app.modules.workouts.models import ExerciseCategory

BASE_DATE = date(2026, 6, 1)
BASE_TIME = datetime(2026, 6, 1, 8, 0, tzinfo=UTC)


def _entry(
    *,
    day_offset: int,
    sets: int = 3,
    reps: int = 8,
    weight: float | None = 40.0,
    workout_id: uuid.UUID | None = None,
    medical: bool = False,
    created_offset_seconds: int = 0,
) -> LoggedSet:
    return LoggedSet(
        workout_id=workout_id or uuid.uuid4(),
        performed_at=BASE_DATE + timedelta(days=day_offset),
        workout_created_at=BASE_TIME + timedelta(seconds=created_offset_seconds),
        sets=sets,
        reps=reps,
        weight_kg=weight,
        has_medical_note=medical,
    )


def _ref(category: ExerciseCategory = ExerciseCategory.STRENGTH, name: str = "Bench Press"):
    return ExerciseRef(id=uuid.uuid4(), name=name, category=category)


def _series(weights: list[float | None], *, day_gap: int = 7, **kwargs) -> list[LoggedSet]:
    """One session per weight, spaced `day_gap` days apart, oldest first."""
    return [
        _entry(day_offset=index * day_gap, weight=weight, **kwargs)
        for index, weight in enumerate(weights)
    ]


# --- Core classifications ------------------------------------------------------


def test_progressing_when_load_rises_across_sessions():
    insight = analytics.build_exercise_insight(_ref(), _series([40.0, 42.5, 45.0]))

    assert insight.classification is TrendClassification.PROGRESSING
    assert insight.metric_basis is MetricBasis.LOAD
    assert insight.sessions_analyzed == 3
    assert insight.current_weight_kg == 45.0
    assert insight.previous_weight_kg == 42.5
    assert insight.primary_change_percent == pytest.approx(12.5)
    assert insight.plateau_detected is False
    assert any("consecutive" in line for line in insight.evidence)


def test_stable_when_changes_are_below_threshold():
    # 100 -> 101 kg is 1%, under the 2% meaningful-change threshold.
    insight = analytics.build_exercise_insight(_ref(), _series([100.0, 100.5, 101.0]))

    assert insight.classification is TrendClassification.STABLE
    assert insight.plateau_detected is False


def test_possible_plateau_after_four_flat_sessions_over_enough_days():
    insight = analytics.build_exercise_insight(_ref(), _series([45.0, 45.0, 45.0, 45.0]))

    assert insight.classification is TrendClassification.POSSIBLE_PLATEAU
    assert insight.plateau_detected is True
    assert insight.sessions_analyzed == 4
    assert insight.date_range is not None
    assert insight.date_range.from_date == BASE_DATE
    assert insight.date_range.to_date == BASE_DATE + timedelta(days=21)


def test_insufficient_data_below_minimum_sessions():
    insight = analytics.build_exercise_insight(_ref(), _series([40.0, 42.5]))

    assert insight.classification is TrendClassification.INSUFFICIENT_DATA
    assert insight.sessions_analyzed == 2
    assert insight.suggestion.kind is SuggestionKind.INSUFFICIENT_HISTORY


def test_declining_when_load_drops_meaningfully():
    insight = analytics.build_exercise_insight(_ref(), _series([60.0, 55.0, 50.0]))

    assert insight.classification is TrendClassification.DECLINING
    assert insight.primary_change_percent == pytest.approx(-16.7, abs=0.1)
    assert insight.suggestion.kind is SuggestionKind.REVIEW_EXERCISE
    assert insight.suggestion.suggested_load_kg is None


# --- Plateau guard rails -------------------------------------------------------


def test_single_repeated_workout_is_not_a_plateau():
    insight = analytics.build_exercise_insight(_ref(), _series([45.0]))
    assert insight.classification is TrendClassification.INSUFFICIENT_DATA


def test_four_flat_sessions_in_one_week_are_not_a_plateau():
    # Same loads, but only 6 days apart end to end — under PLATEAU_MIN_SPAN_DAYS.
    insight = analytics.build_exercise_insight(_ref(), _series([45.0] * 4, day_gap=2))

    assert insight.classification is TrendClassification.STABLE
    assert insight.plateau_detected is False


def test_plateau_wins_over_an_older_jump():
    # An increase five sessions ago followed by four flat sessions is still flat now.
    insight = analytics.build_exercise_insight(
        _ref(), _series([40.0, 45.0, 45.0, 45.0, 45.0])
    )
    assert insight.classification is TrendClassification.POSSIBLE_PLATEAU


def test_plateau_not_declared_when_volume_moves_even_though_load_is_flat():
    # Same load every session, but the last session adds a set — real added work.
    entries = _series([45.0] * 3)
    entries.append(_entry(day_offset=21, sets=5, reps=8, weight=45.0))
    insight = analytics.build_exercise_insight(_ref(), entries)

    assert insight.classification is TrendClassification.STABLE
    assert insight.plateau_detected is False


# --- Boundary values -----------------------------------------------------------


def test_change_exactly_at_threshold_counts_as_a_trend():
    # 100 -> 102 kg is exactly 2.0%, the inclusive threshold.
    insight = analytics.build_exercise_insight(_ref(), _series([100.0, 101.0, 102.0]))
    assert insight.classification is TrendClassification.PROGRESSING


def test_change_just_below_threshold_is_stable():
    insight = analytics.build_exercise_insight(_ref(), _series([100.0, 101.0, 101.9]))
    assert insight.classification is TrendClassification.STABLE


def test_load_up_but_volume_meaningfully_down_is_not_progress():
    entries = [
        _entry(day_offset=0, sets=5, reps=10, weight=40.0),   # volume 2000
        _entry(day_offset=7, sets=4, reps=10, weight=42.5),   # volume 1700
        _entry(day_offset=14, sets=2, reps=8, weight=45.0),   # volume 720
    ]
    insight = analytics.build_exercise_insight(_ref(), entries)

    assert insight.classification is TrendClassification.STABLE
    assert insight.volume_change_percent is not None
    assert insight.volume_change_percent < 0
    assert any("offset by less total work" in line for line in insight.evidence)


def test_floating_point_weights_compare_cleanly():
    insight = analytics.build_exercise_insight(
        _ref(), _series([42.5, 42.5, 42.5, 42.5])
    )
    assert insight.classification is TrendClassification.POSSIBLE_PLATEAU


# --- Non-weighted and non-strength work ----------------------------------------


def test_cardio_is_not_applicable_rather_than_plateaued():
    insight = analytics.build_exercise_insight(
        _ref(ExerciseCategory.CARDIO, "Running"),
        _series([None] * 5),
    )

    assert insight.classification is TrendClassification.NOT_APPLICABLE
    assert insight.metric_basis is MetricBasis.NONE
    assert insight.sessions_analyzed == 0
    assert insight.suggestion.kind is SuggestionKind.INSUFFICIENT_HISTORY


def test_mobility_is_not_applicable():
    insight = analytics.build_exercise_insight(
        _ref(ExerciseCategory.MOBILITY, "Plank"), _series([None] * 4)
    )
    assert insight.classification is TrendClassification.NOT_APPLICABLE


def test_bodyweight_strength_falls_back_to_a_reps_basis():
    entries = [
        _entry(day_offset=0, sets=3, reps=8, weight=None),
        _entry(day_offset=7, sets=3, reps=10, weight=None),
        _entry(day_offset=14, sets=3, reps=12, weight=None),
    ]
    insight = analytics.build_exercise_insight(_ref(name="Pull-up"), entries)

    assert insight.metric_basis is MetricBasis.REPS
    assert insight.classification is TrendClassification.PROGRESSING
    assert insight.current_weight_kg is None
    assert insight.volume_change_percent is None


def test_zero_weight_is_treated_as_unweighted():
    entries = [_entry(day_offset=i * 7, sets=3, reps=10, weight=0.0) for i in range(4)]
    insight = analytics.build_exercise_insight(_ref(name="Push-up"), entries)

    assert insight.metric_basis is MetricBasis.REPS
    sessions = analytics.build_sessions(entries)
    assert all(s.volume_kg is None for s in sessions)


def test_mixed_weighted_and_unweighted_uses_reps_when_weighted_history_is_thin():
    entries = [
        _entry(day_offset=0, weight=None),
        _entry(day_offset=7, weight=40.0),
        _entry(day_offset=14, weight=None),
        _entry(day_offset=21, weight=42.5),
    ]
    insight = analytics.build_exercise_insight(_ref(), entries)
    assert insight.metric_basis is MetricBasis.REPS


# --- Session aggregation edge cases --------------------------------------------


def test_sessions_are_ordered_regardless_of_input_order():
    entries = _series([40.0, 42.5, 45.0])
    shuffled = [entries[2], entries[0], entries[1]]

    sessions = analytics.build_sessions(shuffled)
    assert [s.top_weight_kg for s in sessions] == [40.0, 42.5, 45.0]
    assert analytics.build_exercise_insight(_ref(), shuffled).classification is (
        TrendClassification.PROGRESSING
    )


def test_two_workouts_on_the_same_day_are_two_sessions_ordered_by_creation():
    morning = _entry(day_offset=0, weight=40.0, created_offset_seconds=0)
    evening = _entry(day_offset=0, weight=45.0, created_offset_seconds=3600)

    sessions = analytics.build_sessions([evening, morning])
    assert [s.top_weight_kg for s in sessions] == [40.0, 45.0]
    assert sessions[0].performed_at == sessions[1].performed_at


def test_repeated_exercise_within_one_workout_aggregates_into_one_session():
    workout_id = uuid.uuid4()
    entries = [
        _entry(day_offset=0, sets=3, reps=8, weight=40.0, workout_id=workout_id),
        _entry(day_offset=0, sets=1, reps=5, weight=50.0, workout_id=workout_id),
    ]
    sessions = analytics.build_sessions(entries)

    assert len(sessions) == 1
    assert sessions[0].total_sets == 4
    assert sessions[0].total_reps == 29           # 3x8 + 1x5
    assert sessions[0].reps_per_set is None       # rep counts differed
    assert sessions[0].top_weight_kg == 50.0
    assert sessions[0].volume_kg == pytest.approx(3 * 8 * 40.0 + 1 * 5 * 50.0)


def test_reps_per_set_is_reported_when_every_row_matches():
    sessions = analytics.build_sessions([_entry(day_offset=0, sets=3, reps=8)])
    assert sessions[0].reps_per_set == 8
    assert sessions[0].total_reps == 24


def test_varying_set_counts_are_reflected_in_volume():
    sessions = analytics.build_sessions(
        [
            _entry(day_offset=0, sets=3, reps=8, weight=40.0),
            _entry(day_offset=7, sets=5, reps=8, weight=40.0),
        ]
    )
    assert sessions[0].volume_kg == pytest.approx(960.0)
    assert sessions[1].volume_kg == pytest.approx(1600.0)


def test_only_the_most_recent_window_of_sessions_is_analyzed():
    entries = _series([20.0, 25.0, 30.0, 45.0, 45.0, 45.0, 45.0])
    insight = analytics.build_exercise_insight(_ref(), entries)

    assert insight.sessions_analyzed == rules.TREND_WINDOW_SESSIONS
    assert insight.classification is TrendClassification.POSSIBLE_PLATEAU


# --- Personal bests ------------------------------------------------------------


def test_personal_bests_from_weighted_history():
    entries = [
        _entry(day_offset=0, sets=3, reps=8, weight=40.0),    # volume 960
        _entry(day_offset=7, sets=5, reps=8, weight=45.0),    # volume 1800, heaviest
        _entry(day_offset=14, sets=3, reps=8, weight=45.0),   # volume 1080
    ]
    bests = analytics.find_personal_bests(analytics.build_sessions(entries))

    assert bests.heaviest_weight_kg == 45.0
    assert bests.heaviest_weight_on == BASE_DATE + timedelta(days=7)
    assert bests.best_session_volume_kg == pytest.approx(1800.0)
    assert bests.most_reps_at_heaviest_weight == 40      # 5x8 at 45 kg


def test_personal_bests_are_empty_without_weighted_history():
    bests = analytics.find_personal_bests(
        analytics.build_sessions(_series([None] * 3))
    )
    assert bests.heaviest_weight_kg is None
    assert bests.best_session_volume_kg is None
    assert bests.most_reps_at_heaviest_weight is None


def test_personal_bests_of_empty_history_invent_nothing():
    bests = analytics.find_personal_bests([])
    assert bests.model_dump(exclude_none=True) == {}


# --- Suggestions ---------------------------------------------------------------


@pytest.mark.parametrize(
    ("current", "expected"),
    [
        (45.0, 46.0),     # 2.5% = 1.125 kg -> rounded to 1.0
        (100.0, 102.5),   # 2.5% = 2.5 kg, at the cap
        (200.0, 202.5),   # 2.5% = 5 kg, clamped to the 2.5 kg cap
        (10.0, 10.5),     # 2.5% = 0.25 kg, raised to the 0.5 kg floor
    ],
)
def test_suggested_load_is_conservative_and_bounded(current, expected):
    assert analytics.suggest_next_load_kg(current) == pytest.approx(expected)
    step = analytics.suggest_next_load_kg(current) - current
    assert rules.MIN_LOAD_INCREASE_KG <= step <= rules.MAX_LOAD_INCREASE_KG


def test_no_load_suggested_without_a_current_load():
    assert analytics.suggest_next_load_kg(0.0) is None


def test_stable_load_based_exercise_gets_a_small_progression():
    insight = analytics.build_exercise_insight(_ref(), _series([100.0, 100.5, 101.0]))

    assert insight.suggestion.kind is SuggestionKind.SMALL_PROGRESSION
    assert insight.suggestion.current_load_kg == 101.0
    assert insight.suggestion.suggested_load_kg == pytest.approx(103.5)


def test_progressing_exercise_is_told_to_maintain_not_to_add_load():
    insight = analytics.build_exercise_insight(_ref(), _series([40.0, 42.5, 45.0]))

    assert insight.suggestion.kind is SuggestionKind.MAINTAIN
    assert insight.suggestion.suggested_load_kg is None


def test_plateau_suggests_review_rather_than_more_load():
    insight = analytics.build_exercise_insight(_ref(), _series([45.0] * 4))

    assert insight.suggestion.kind is SuggestionKind.REVIEW_EXERCISE
    assert insight.suggestion.suggested_load_kg is None


# --- Medical boundary ----------------------------------------------------------


@pytest.mark.parametrize(
    "note",
    ["Sharp pain in my shoulder", "Elbow hurts", "Old rotator cuff injury", "Physio said to stop"],
)
def test_pain_and_injury_notes_are_detected(note):
    assert rules.mentions_medical_concern(note) is True


@pytest.mark.parametrize(
    "note", [None, "", "Felt strong today", "Training hard, no complaints", "Restrained pace"]
)
def test_ordinary_notes_are_not_flagged(note):
    assert rules.mentions_medical_concern(note) is False


def test_medical_note_suppresses_the_training_suggestion():
    entries = _series([100.0, 100.5, 101.0])
    entries[-1] = LoggedSet(
        workout_id=entries[-1].workout_id,
        performed_at=entries[-1].performed_at,
        workout_created_at=entries[-1].workout_created_at,
        sets=entries[-1].sets,
        reps=entries[-1].reps,
        weight_kg=entries[-1].weight_kg,
        has_medical_note=True,
    )
    insight = analytics.build_exercise_insight(_ref(), entries)

    # The deterministic trend is still reported — only the prescription is withheld.
    assert insight.classification is TrendClassification.STABLE
    assert insight.suggestion.kind is SuggestionKind.CONSULT_PROFESSIONAL
    assert insight.suggestion.suggested_load_kg is None
    assert "qualified professional" in insight.suggestion.message


# --- Consistency ---------------------------------------------------------------


def test_consistency_counts_this_week_against_last_week():
    today = date(2026, 9, 17)
    dates = [
        today,
        today - timedelta(days=2),
        today - timedelta(days=5),
        today - timedelta(days=8),
        today - timedelta(days=11),
    ]
    metrics = analytics.compute_consistency(dates, today=today)

    assert metrics.workouts_last_7_days == 3
    assert metrics.workouts_previous_7_days == 2
    assert metrics.change == 1
    assert metrics.weeks_analyzed == rules.CONSISTENCY_TREND_WEEKS


def test_consistency_ignores_future_dated_workouts():
    today = date(2026, 9, 17)
    metrics = analytics.compute_consistency([today + timedelta(days=3), today], today=today)
    assert metrics.workouts_last_7_days == 1


def test_consistency_of_an_empty_history_is_all_zero():
    metrics = analytics.compute_consistency([], today=date(2026, 9, 17))
    assert metrics.workouts_last_7_days == 0
    assert metrics.average_workouts_per_week == 0.0
    assert metrics.active_weeks == 0


# --- Volume trend --------------------------------------------------------------


def test_volume_trend_compares_two_seven_day_windows():
    today = date(2026, 9, 17)
    entries = [
        LoggedSet(uuid.uuid4(), today - timedelta(days=1), BASE_TIME, 3, 10, 50.0),
        LoggedSet(uuid.uuid4(), today - timedelta(days=9), BASE_TIME, 3, 10, 40.0),
    ]
    trend = analytics.compute_volume_trend(entries, today=today)

    assert trend.current_7_day_volume_kg == pytest.approx(1500.0)
    assert trend.previous_7_day_volume_kg == pytest.approx(1200.0)
    assert trend.change_percent == pytest.approx(25.0)


def test_volume_trend_skips_rows_without_a_weight():
    today = date(2026, 9, 17)
    entries = [LoggedSet(uuid.uuid4(), today, BASE_TIME, 3, 10, None)]
    trend = analytics.compute_volume_trend(entries, today=today)

    assert trend.current_7_day_volume_kg == 0.0
    assert trend.change_percent is None


def test_volume_change_is_none_rather_than_infinite_from_a_zero_baseline():
    today = date(2026, 9, 17)
    entries = [LoggedSet(uuid.uuid4(), today, BASE_TIME, 3, 10, 50.0)]
    trend = analytics.compute_volume_trend(entries, today=today)

    assert trend.previous_7_day_volume_kg == 0.0
    assert trend.change_percent is None


# --- Fallback explanation ------------------------------------------------------


def test_fallback_explanation_matches_the_computed_verdict():
    insight = analytics.build_exercise_insight(_ref(), _series([40.0, 42.5, 45.0]))

    assert insight.explanation_source == "deterministic"
    assert "progressing" in insight.explanation.lower()
    assert "Bench Press" in insight.explanation


def test_fallback_explanation_exists_for_every_classification():
    cases = [
        _series([40.0, 42.5, 45.0]),
        _series([100.0, 100.5, 101.0]),
        _series([45.0] * 4),
        _series([60.0, 55.0, 50.0]),
        _series([40.0]),
    ]
    for entries in cases:
        insight = analytics.build_exercise_insight(_ref(), entries)
        assert insight.explanation.strip()
