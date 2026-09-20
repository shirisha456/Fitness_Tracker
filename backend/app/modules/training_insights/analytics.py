"""The deterministic analytics engine.

Every function here is pure: no database, no network, no clock. `today` is always
passed in, so every result is reproducible and unit-testable without fixtures.
The rules these functions implement are documented in `rules.py`; this file
contains no bare numbers.
"""

from __future__ import annotations

import uuid
from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from app.modules.training_insights.rules import (
    CONSISTENCY_TREND_WEEKS,
    CONSISTENCY_WINDOW_DAYS,
    LOAD_ROUNDING_KG,
    MAX_LOAD_INCREASE_KG,
    MEDICAL_REFERRAL_MESSAGE,
    MIN_LOAD_INCREASE_KG,
    MIN_MEANINGFUL_CHANGE_PCT,
    MIN_SESSIONS_FOR_TREND,
    PLATEAU_MIN_SESSIONS,
    PLATEAU_MIN_SPAN_DAYS,
    PROGRESSION_STEP_PCT,
    TREND_WINDOW_SESSIONS,
    WEIGHT_COMPARISON_EPSILON_KG,
    MetricBasis,
    SuggestionKind,
    TrendClassification,
)
from app.modules.training_insights.schemas import (
    ConsistencyMetrics,
    DateRange,
    ExerciseInsight,
    ExerciseRef,
    NextSessionSuggestion,
    PersonalBests,
    SessionMetrics,
    VolumeTrend,
)
from app.modules.workouts.models import ExerciseCategory


@dataclass(frozen=True, slots=True)
class LoggedSet:
    """One `workout_exercises` row flattened with its parent workout's identity.

    This is the engine's only input shape. `has_medical_note` is a boolean the
    database layer derives from the free-text notes — the note text itself never
    enters the analytics layer, so it cannot reach a log, an error message or the
    AI prompt.
    """

    workout_id: uuid.UUID
    performed_at: date
    workout_created_at: datetime
    sets: int
    reps: int
    weight_kg: float | None
    has_medical_note: bool = False


# --- Small numeric helpers -----------------------------------------------------


def _is_weighted(weight_kg: float | None) -> bool:
    """A weight of exactly 0 is a valid input the API accepts (`ge=0`) but it
    carries no load information, so it is treated as unweighted."""
    return weight_kg is not None and weight_kg > WEIGHT_COMPARISON_EPSILON_KG


def _same_weight(left: float, right: float) -> bool:
    return abs(left - right) < WEIGHT_COMPARISON_EPSILON_KG


def percent_change(baseline: float | None, latest: float | None) -> float | None:
    """Percentage change, or None when it cannot be expressed.

    A zero baseline has no meaningful percentage change, so it returns None
    rather than infinity — callers treat None as "no signal", never as "no
    change".
    """
    if baseline is None or latest is None:
        return None
    if abs(baseline) < WEIGHT_COMPARISON_EPSILON_KG:
        return None
    return round((latest - baseline) / baseline * 100.0, 1)


def _is_meaningful(change_percent: float | None) -> bool:
    return change_percent is not None and abs(change_percent) >= MIN_MEANINGFUL_CHANGE_PCT


def _spread_percent(values: Sequence[float]) -> float | None:
    """Peak-to-trough spread of a series as a percentage of its smallest value."""
    if not values:
        return None
    low, high = min(values), max(values)
    if abs(low) < WEIGHT_COMPARISON_EPSILON_KG:
        return None
    return round((high - low) / low * 100.0, 1)


# --- Session aggregation -------------------------------------------------------


def build_sessions(entries: Iterable[LoggedSet]) -> list[SessionMetrics]:
    """Collapse raw rows into one `SessionMetrics` per workout, oldest first.

    Handles the awkward realities of logged data: rows arriving in any order,
    several workouts sharing a date, and the same exercise appearing more than
    once inside one workout (a drop set, or a user who logged their warm-up
    separately). Ordering is `(performed_at, workout_created_at, workout_id)` so
    the result is stable even when two workouts share a date.
    """
    grouped: dict[uuid.UUID, list[LoggedSet]] = defaultdict(list)
    for entry in entries:
        grouped[entry.workout_id].append(entry)

    sessions: list[SessionMetrics] = []
    for rows in grouped.values():
        first = rows[0]
        total_sets = sum(row.sets for row in rows)
        total_reps = sum(row.sets * row.reps for row in rows)

        distinct_reps = {row.reps for row in rows}
        reps_per_set = distinct_reps.pop() if len(distinct_reps) == 1 else None

        weighted = [row for row in rows if _is_weighted(row.weight_kg)]
        top_weight = max((row.weight_kg for row in weighted), default=None)
        volume = (
            round(sum(row.sets * row.reps * row.weight_kg for row in weighted), 2)
            if weighted
            else None
        )

        sessions.append(
            SessionMetrics(
                workout_id=first.workout_id,
                performed_at=first.performed_at,
                total_sets=total_sets,
                total_reps=total_reps,
                reps_per_set=reps_per_set,
                top_weight_kg=top_weight,
                volume_kg=volume,
            )
        )

    order: dict[uuid.UUID, LoggedSet] = {rows[0].workout_id: rows[0] for rows in grouped.values()}
    sessions.sort(
        key=lambda s: (s.performed_at, order[s.workout_id].workout_created_at, str(s.workout_id))
    )
    return sessions


def session_has_medical_note(entries: Iterable[LoggedSet]) -> bool:
    return any(entry.has_medical_note for entry in entries)


# --- Personal bests ------------------------------------------------------------


def find_personal_bests(sessions: Sequence[SessionMetrics]) -> PersonalBests:
    """Records over whatever history was queried.

    Returns empty fields rather than inventing a record when no session in the
    history carried a weight.
    """
    weighted = [s for s in sessions if s.top_weight_kg is not None]
    if not weighted:
        return PersonalBests()

    # Ties go to the earliest date: a record is dated from when it was first set,
    # not from the last time it was matched.
    def _earliest_best(candidates, value):
        return max(candidates, key=lambda s: (value(s), -s.performed_at.toordinal()))

    heaviest = _earliest_best(weighted, lambda s: s.top_weight_kg)
    heaviest_weight = heaviest.top_weight_kg
    assert heaviest_weight is not None

    with_volume = [s for s in sessions if s.volume_kg is not None]
    best_volume = (
        _earliest_best(with_volume, lambda s: s.volume_kg) if with_volume else None
    )

    # "Most reps at your heaviest load" — only compares sessions at that load, so
    # a light high-rep day can never masquerade as a record here.
    at_heaviest = [
        s for s in weighted if _same_weight(s.top_weight_kg or 0.0, heaviest_weight)
    ]
    most_reps = _earliest_best(at_heaviest, lambda s: s.total_reps)

    return PersonalBests(
        heaviest_weight_kg=heaviest_weight,
        heaviest_weight_on=heaviest.performed_at,
        best_session_volume_kg=best_volume.volume_kg if best_volume else None,
        best_session_volume_on=best_volume.performed_at if best_volume else None,
        most_reps_at_heaviest_weight=most_reps.total_reps,
        most_reps_at_heaviest_weight_on=most_reps.performed_at,
    )


# --- Trend classification ------------------------------------------------------


@dataclass(frozen=True, slots=True)
class _Window:
    """The sessions a verdict is actually computed from, plus which metric drives it."""

    sessions: list[SessionMetrics]
    basis: MetricBasis

    def primary(self, session: SessionMetrics) -> float | None:
        if self.basis is MetricBasis.LOAD:
            return session.top_weight_kg
        if self.basis is MetricBasis.REPS:
            return float(session.total_reps)
        return None

    def primary_series(self) -> list[float]:
        return [value for s in self.sessions if (value := self.primary(s)) is not None]

    def volume_series(self) -> list[float]:
        if self.basis is not MetricBasis.LOAD:
            return []
        return [s.volume_kg for s in self.sessions if s.volume_kg is not None]


def _select_window(sessions: Sequence[SessionMetrics]) -> _Window:
    """Pick the analysis window and the metric that drives classification.

    Load wins when there is enough weighted history to trust it. Otherwise the
    engine falls back to total reps, which is the only honest signal for a
    bodyweight strength exercise such as a push-up or pull-up.
    """
    window = list(sessions[-TREND_WINDOW_SESSIONS:])
    weighted = [s for s in window if s.top_weight_kg is not None]
    if len(weighted) >= MIN_SESSIONS_FOR_TREND:
        return _Window(sessions=weighted, basis=MetricBasis.LOAD)
    return _Window(sessions=window, basis=MetricBasis.REPS)


def _format_kg(value: float) -> str:
    """Trim a trailing .0 so evidence reads '45 kg', not '45.0 kg'."""
    return f"{value:g}"


def _plateau_verdict(window: _Window) -> tuple[bool, list[str]]:
    """Is the tail of the window flat for long enough to call a plateau?"""
    if len(window.sessions) < PLATEAU_MIN_SESSIONS:
        return False, []

    tail = window.sessions[-PLATEAU_MIN_SESSIONS:]
    span_days = (tail[-1].performed_at - tail[0].performed_at).days
    if span_days < PLATEAU_MIN_SPAN_DAYS:
        # Four hard sessions in one week is a training block, not a plateau.
        return False, []

    tail_window = _Window(sessions=tail, basis=window.basis)
    primary_spread = _spread_percent(tail_window.primary_series())
    if primary_spread is None or primary_spread >= MIN_MEANINGFUL_CHANGE_PCT:
        return False, []

    volume_spread = _spread_percent(tail_window.volume_series())
    if volume_spread is not None and volume_spread >= MIN_MEANINGFUL_CHANGE_PCT:
        return False, []

    metric_label = "load" if window.basis is MetricBasis.LOAD else "total reps"
    evidence = [
        f"No meaningful change in {metric_label} across the last "
        f"{PLATEAU_MIN_SESSIONS} comparable sessions",
        f"Those sessions span {span_days} days "
        f"(a plateau needs at least {PLATEAU_MIN_SPAN_DAYS})",
        f"{metric_label.capitalize()} varied by {primary_spread}%, "
        f"under the {MIN_MEANINGFUL_CHANGE_PCT}% threshold",
    ]
    if volume_spread is not None:
        evidence.append(
            f"Session volume varied by {volume_spread}%, "
            f"under the {MIN_MEANINGFUL_CHANGE_PCT}% threshold"
        )
    return True, evidence


def classify(
    sessions: Sequence[SessionMetrics], category: ExerciseCategory
) -> tuple[TrendClassification, _Window, list[str]]:
    """Return the verdict, the window it was computed from, and the evidence."""
    if category is not ExerciseCategory.STRENGTH:
        return (
            TrendClassification.NOT_APPLICABLE,
            _Window(sessions=[], basis=MetricBasis.NONE),
            [
                f"Trend analysis is defined for strength exercises; logged sets and reps "
                f"do not describe a comparable workload for {category.value} work"
            ],
        )

    window = _select_window(sessions)
    if len(window.sessions) < MIN_SESSIONS_FOR_TREND:
        return (
            TrendClassification.INSUFFICIENT_DATA,
            window,
            [
                f"{len(window.sessions)} comparable session"
                f"{'' if len(window.sessions) == 1 else 's'} logged; "
                f"at least {MIN_SESSIONS_FOR_TREND} are needed to classify a trend"
            ],
        )

    is_plateau, plateau_evidence = _plateau_verdict(window)
    if is_plateau:
        return TrendClassification.POSSIBLE_PLATEAU, window, plateau_evidence

    first, last = window.sessions[0], window.sessions[-1]
    primary_change = percent_change(window.primary(first), window.primary(last))
    volume_change = percent_change(first.volume_kg, last.volume_kg)
    metric_label = "Top load" if window.basis is MetricBasis.LOAD else "Total reps"
    unit = " kg" if window.basis is MetricBasis.LOAD else ""

    baseline_value = window.primary(first)
    latest_value = window.primary(last)
    change_line = (
        f"{metric_label} moved from {_format_kg(baseline_value)}{unit} to "
        f"{_format_kg(latest_value)}{unit} across {len(window.sessions)} sessions "
        f"({primary_change:+}%)"
        if baseline_value is not None and latest_value is not None and primary_change is not None
        else f"{metric_label} could not be compared across the window"
    )

    if _is_meaningful(primary_change) and primary_change > 0:
        if volume_change is not None and volume_change <= -MIN_MEANINGFUL_CHANGE_PCT:
            # Heavier but meaningfully less total work: a trade-off, not progress.
            return (
                TrendClassification.STABLE,
                window,
                [
                    change_line,
                    f"Session volume fell {volume_change}%, so the increase in load "
                    f"was offset by less total work",
                ],
            )
        evidence = [change_line]
        streak = _consecutive_increases(window)
        if streak >= 2:
            evidence.append(f"{metric_label} increased in {streak} consecutive sessions")
        if volume_change is not None:
            evidence.append(f"Session volume changed by {volume_change:+}%")
        return TrendClassification.PROGRESSING, window, evidence

    if _is_meaningful(primary_change) and primary_change < 0:
        evidence = [change_line]
        if volume_change is not None:
            evidence.append(f"Session volume changed by {volume_change:+}%")
        return TrendClassification.DECLINING, window, evidence

    evidence = [change_line]
    if primary_change is not None:
        evidence.append(
            f"That is under the {MIN_MEANINGFUL_CHANGE_PCT}% threshold for a trend"
        )
    if volume_change is not None:
        evidence.append(f"Session volume changed by {volume_change:+}%")
    if len(window.sessions) < PLATEAU_MIN_SESSIONS:
        evidence.append(
            f"{PLATEAU_MIN_SESSIONS} sessions are needed before a plateau can be reported"
        )
    return TrendClassification.STABLE, window, evidence


def _consecutive_increases(window: _Window) -> int:
    """Longest run of strictly increasing steps ending at the latest session."""
    values = window.primary_series()
    streak = 0
    for older, newer in zip(reversed(values[:-1]), reversed(values[1:]), strict=False):
        if newer - older > WEIGHT_COMPARISON_EPSILON_KG:
            streak += 1
        else:
            break
    return streak


# --- Next-session suggestions --------------------------------------------------


def suggest_next_load_kg(current_load_kg: float) -> float | None:
    """A conservative, hard-bounded next load, or None if one cannot be formed.

    The raw step is `PROGRESSION_STEP_PCT` of the current load, clamped into
    [`MIN_LOAD_INCREASE_KG`, `MAX_LOAD_INCREASE_KG`] and rounded to
    `LOAD_ROUNDING_KG` so the number is actually loadable.
    """
    if current_load_kg <= WEIGHT_COMPARISON_EPSILON_KG:
        return None
    raw_step = current_load_kg * PROGRESSION_STEP_PCT / 100.0
    clamped = min(max(raw_step, MIN_LOAD_INCREASE_KG), MAX_LOAD_INCREASE_KG)
    step = round(clamped / LOAD_ROUNDING_KG) * LOAD_ROUNDING_KG
    if step <= 0:
        return None
    return round(current_load_kg + step, 2)


def build_suggestion(
    classification: TrendClassification,
    window: _Window,
    *,
    has_medical_note: bool,
) -> NextSessionSuggestion:
    """Map a verdict onto a conservative suggestion. Never auto-applied."""
    if has_medical_note:
        return NextSessionSuggestion(
            kind=SuggestionKind.CONSULT_PROFESSIONAL,
            message=MEDICAL_REFERRAL_MESSAGE,
            rationale=(
                "A recent note for this exercise mentions pain or injury, which this "
                "application is not qualified to act on."
            ),
        )

    if classification in (
        TrendClassification.INSUFFICIENT_DATA,
        TrendClassification.NOT_APPLICABLE,
    ):
        return NextSessionSuggestion(
            kind=SuggestionKind.INSUFFICIENT_HISTORY,
            message="Not enough comparable history to suggest a change yet.",
            rationale=(
                f"A suggestion needs at least {MIN_SESSIONS_FOR_TREND} comparable "
                f"strength sessions for this exercise."
            ),
        )

    if classification is TrendClassification.PROGRESSING:
        return NextSessionSuggestion(
            kind=SuggestionKind.MAINTAIN,
            message="Keep doing what you're doing — your current progression is working.",
            rationale="Recent sessions already show a meaningful upward trend.",
        )

    if classification in (
        TrendClassification.POSSIBLE_PLATEAU,
        TrendClassification.DECLINING,
    ):
        return NextSessionSuggestion(
            kind=SuggestionKind.REVIEW_EXERCISE,
            message=(
                "Worth reviewing this exercise — consider changing the rep range, "
                "adjusting how much you do, or swapping in a variation."
            ),
            rationale=(
                "Adding load is not the right response to a flat or falling trend, so "
                "no load increase is suggested."
            ),
        )

    # Stable.
    latest = window.sessions[-1] if window.sessions else None
    current = latest.top_weight_kg if latest else None
    if window.basis is MetricBasis.LOAD and current is not None:
        suggested = suggest_next_load_kg(current)
        if suggested is not None:
            return NextSessionSuggestion(
                kind=SuggestionKind.SMALL_PROGRESSION,
                message=(
                    f"If the last session felt manageable, you could try "
                    f"{_format_kg(suggested)} kg next time."
                ),
                rationale=(
                    f"Performance has been steady, so this suggests about "
                    f"{PROGRESSION_STEP_PCT}% more load — capped at "
                    f"{_format_kg(MAX_LOAD_INCREASE_KG)} kg. It is a suggestion only; "
                    f"nothing changes unless you apply it."
                ),
                current_load_kg=current,
                suggested_load_kg=suggested,
            )

    return NextSessionSuggestion(
        kind=SuggestionKind.MAINTAIN,
        message="Maintain your current workload.",
        rationale="Recent sessions have been steady, with no change large enough to act on.",
    )


# --- Assembly ------------------------------------------------------------------


def build_exercise_insight(
    exercise: ExerciseRef,
    entries: Sequence[LoggedSet],
) -> ExerciseInsight:
    """The single entry point that turns raw rows into a full, explained verdict."""
    sessions = build_sessions(entries)
    classification, window, evidence = classify(sessions, exercise.category)
    has_medical_note = session_has_medical_note(entries)
    suggestion = build_suggestion(classification, window, has_medical_note=has_medical_note)

    analyzed = window.sessions
    date_range = (
        DateRange(from_date=analyzed[0].performed_at, to_date=analyzed[-1].performed_at)
        if analyzed
        else None
    )
    if date_range is not None:
        evidence = [
            *evidence,
            f"Analysis window: {date_range.from_date.isoformat()} to "
            f"{date_range.to_date.isoformat()}",
        ]

    first = analyzed[0] if analyzed else None
    last = analyzed[-1] if analyzed else None
    primary_change = (
        percent_change(window.primary(first), window.primary(last))
        if first is not None and last is not None
        else None
    )
    volume_change = (
        percent_change(first.volume_kg, last.volume_kg)
        if first is not None and last is not None
        else None
    )

    insight = ExerciseInsight(
        exercise=exercise,
        classification=classification,
        metric_basis=window.basis,
        sessions_analyzed=len(analyzed),
        date_range=date_range,
        current_weight_kg=last.top_weight_kg if last else None,
        previous_weight_kg=(
            analyzed[-2].top_weight_kg if len(analyzed) >= 2 else None
        ),
        primary_change_percent=primary_change,
        volume_change_percent=volume_change,
        plateau_detected=classification is TrendClassification.POSSIBLE_PLATEAU,
        evidence=evidence,
        suggestion=suggestion,
        explanation="",
    )
    insight.explanation = build_fallback_explanation(insight)
    return insight


def build_fallback_explanation(insight: ExerciseInsight) -> str:
    """Plain-language prose built from the computed numbers alone.

    This is what the feature shows when OpenAI is not configured, is down, or was
    simply not asked — which is why analytics never depend on AI availability.
    """
    name = insight.exercise.name
    count = insight.sessions_analyzed

    if insight.classification is TrendClassification.NOT_APPLICABLE:
        return (
            f"{name} is logged as {insight.exercise.category.value} work, and sets and reps "
            f"alone don't describe a comparable workload for it, so no trend is reported."
        )
    if insight.classification is TrendClassification.INSUFFICIENT_DATA:
        return (
            f"There {'is' if count == 1 else 'are'} {count} comparable "
            f"{name} session{'' if count == 1 else 's'} on record. "
            f"{MIN_SESSIONS_FOR_TREND} are needed before a trend can be reported."
        )

    if insight.metric_basis is MetricBasis.LOAD:
        detail = (
            f"top load went from {_format_kg(insight.previous_weight_kg)} kg to "
            f"{_format_kg(insight.current_weight_kg)} kg"
            if insight.previous_weight_kg is not None and insight.current_weight_kg is not None
            else "load stayed in the same range"
        )
    else:
        detail = "this is based on total reps, since these sessions were logged without a weight"

    if insight.classification is TrendClassification.PROGRESSING:
        return (
            f"Your {name} is progressing: across the last {count} sessions, {detail}, "
            f"and total session volume held up."
        )
    if insight.classification is TrendClassification.POSSIBLE_PLATEAU:
        return (
            f"Your {name} may have plateaued: the last {PLATEAU_MIN_SESSIONS} sessions show "
            f"no meaningful change in load, reps or volume over at least "
            f"{PLATEAU_MIN_SPAN_DAYS} days."
        )
    if insight.classification is TrendClassification.DECLINING:
        return (
            f"Your {name} has trended down across the last {count} sessions — {detail}. "
            f"That is often just a normal fluctuation in training."
        )
    return (
        f"Your {name} has been steady across the last {count} sessions, with no change "
        f"large enough to count as a trend."
    )


# --- Consistency and workload --------------------------------------------------


def compute_consistency(
    workout_dates: Sequence[date],
    *,
    today: date,
) -> ConsistencyMetrics:
    """Factual counts only — how often the user trained, not how recovered they are.

    Future-dated workouts are ignored: the schema has no planned/completed flag,
    so a workout dated ahead of today cannot be counted as something that happened.
    """
    performed = [d for d in workout_dates if d <= today]

    current_start = today - timedelta(days=CONSISTENCY_WINDOW_DAYS - 1)
    previous_start = current_start - timedelta(days=CONSISTENCY_WINDOW_DAYS)

    last_7 = sum(1 for d in performed if current_start <= d <= today)
    previous_7 = sum(1 for d in performed if previous_start <= d < current_start)

    trend_start = today - timedelta(days=CONSISTENCY_TREND_WEEKS * 7 - 1)
    in_trend = [d for d in performed if trend_start <= d <= today]
    active_weeks = len({(d - trend_start).days // 7 for d in in_trend})
    average = round(len(in_trend) / CONSISTENCY_TREND_WEEKS, 1)

    return ConsistencyMetrics(
        workouts_last_7_days=last_7,
        workouts_previous_7_days=previous_7,
        change=last_7 - previous_7,
        active_weeks=active_weeks,
        weeks_analyzed=CONSISTENCY_TREND_WEEKS,
        average_workouts_per_week=average,
    )


def compute_volume_trend(entries: Iterable[LoggedSet], *, today: date) -> VolumeTrend:
    """Weighted volume over the current vs previous 7 days.

    Rows without a weight contribute nothing — there is no defensible way to add
    a bodyweight plank to a barbell squat.
    """
    current_start = today - timedelta(days=CONSISTENCY_WINDOW_DAYS - 1)
    previous_start = current_start - timedelta(days=CONSISTENCY_WINDOW_DAYS)

    current = 0.0
    previous = 0.0
    for entry in entries:
        if not _is_weighted(entry.weight_kg):
            continue
        volume = entry.sets * entry.reps * (entry.weight_kg or 0.0)
        if current_start <= entry.performed_at <= today:
            current += volume
        elif previous_start <= entry.performed_at < current_start:
            previous += volume

    return VolumeTrend(
        current_7_day_volume_kg=round(current, 2),
        previous_7_day_volume_kg=round(previous, 2),
        change_percent=percent_change(previous, current),
    )
