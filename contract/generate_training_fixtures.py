"""Generate the authoritative training-insight fixtures from the Python engine.

    backend/.venv/bin/python contract/generate_training_fixtures.py

Both implementations consume contract/training-insights-fixtures.json:

  * pytest  -> backend/tests/unit/test_training_fixture_parity.py
  * JUnit   -> backend-java/.../TrainingAnalyticsFixtureTest.java

The Python output is the source of truth. That matters most for two things the two
languages do NOT agree on by default:

  * rounding — CPython's round() is half-to-even over the exact binary value of the
    double, which Math.round and BigDecimal.valueOf both get wrong on ties;
  * evidence strings — Python's f"{v:g}" and f"{v:+}" formatting.

So the fixtures record the full computed result, not just the classification: every
metric, every evidence line, the suggestion, and the fallback explanation. If the Java
port diverges anywhere, the JUnit run fails with the exact field.

Regenerate only when the Python engine's behaviour deliberately changes.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
import uuid
from datetime import UTC, date, datetime, timedelta

os.environ.setdefault("SECRET_KEY", "fixture-generation-only-secret-key-0123456789")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://unused@/unused")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.modules.training_insights import analytics  # noqa: E402
from app.modules.training_insights.analytics import LoggedSet  # noqa: E402
from app.modules.workouts.models import ExerciseCategory  # noqa: E402

BASE_DATE = date(2026, 6, 1)
BASE_TIME = datetime(2026, 6, 1, 8, 0, tzinfo=UTC)
# Deterministic ids so regeneration produces a stable diff.
WORKOUT_NAMESPACE = uuid.UUID("00000000-0000-0000-0000-00000000f17e")


def workout_id(index: int) -> uuid.UUID:
    return uuid.uuid5(WORKOUT_NAMESPACE, str(index))


class SessionBuilder:
    """Accumulates LoggedSet rows and the JSON description of them."""

    def __init__(self) -> None:
        self.rows: list[LoggedSet] = []
        self.described: list[dict] = []
        self._index = 0

    def add(self, *, day_offset: int, sets: int = 3, reps: int = 8,
            weight: float | None = 40.0, medical: bool = False,
            created_offset_seconds: int = 0, same_workout_as: int | None = None) -> SessionBuilder:
        if same_workout_as is None:
            self._index += 1
            index = self._index
        else:
            index = same_workout_as
        wid = workout_id(index)
        self.rows.append(LoggedSet(
            workout_id=wid,
            performed_at=BASE_DATE + timedelta(days=day_offset),
            workout_created_at=BASE_TIME + timedelta(seconds=created_offset_seconds),
            sets=sets, reps=reps, weight_kg=weight, has_medical_note=medical,
        ))
        self.described.append({
            "workout_id": str(wid),
            "performed_at": (BASE_DATE + timedelta(days=day_offset)).isoformat(),
            "workout_created_at": (BASE_TIME + timedelta(seconds=created_offset_seconds))
                .isoformat().replace("+00:00", "Z"),
            "sets": sets, "reps": reps, "weight_kg": weight,
            "has_medical_note": medical,
        })
        return self

    def series(self, weights: list[float | None], *, day_gap: int = 7, **kwargs) -> SessionBuilder:
        for offset, weight in enumerate(weights):
            self.add(day_offset=offset * day_gap, weight=weight, **kwargs)
        return self


def serialise(value):
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if hasattr(value, "value"):
        return value.value
    return value


def insight_to_json(insight) -> dict:
    """The full computed result, using the same field names the API emits."""
    payload = insight.model_dump(mode="json", by_alias=True)
    return payload


CASES: list[dict] = []


def case(name: str, description: str, builder: SessionBuilder,
         category: ExerciseCategory = ExerciseCategory.STRENGTH,
         exercise_name: str = "Bench Press") -> None:
    from app.modules.training_insights.schemas import ExerciseRef

    ref = ExerciseRef(
        id=uuid.uuid5(WORKOUT_NAMESPACE, exercise_name),
        name=exercise_name,
        category=category,
    )
    insight = analytics.build_exercise_insight(ref, builder.rows)
    sessions = analytics.build_sessions(builder.rows)
    bests = analytics.find_personal_bests(sessions)

    CASES.append({
        "name": name,
        "description": description,
        "exercise": {"id": str(ref.id), "name": ref.name, "category": category.value},
        "logged_sets": builder.described,
        "expected": {
            "insight": insight_to_json(insight),
            "sessions": [s.model_dump(mode="json") for s in sessions],
            "personal_bests": bests.model_dump(mode="json"),
        },
    })


def build_cases() -> None:
    # --- the six classifications ----------------------------------------------
    case("progressing_load", "load rises across three comparable sessions",
         SessionBuilder().series([40.0, 42.5, 45.0]))
    case("stable_below_threshold", "changes under the 2% meaningful-change threshold",
         SessionBuilder().series([100.0, 100.5, 101.0]))
    case("possible_plateau", "four flat sessions spanning 21 days",
         SessionBuilder().series([45.0, 45.0, 45.0, 45.0]))
    case("declining", "load drops meaningfully",
         SessionBuilder().series([60.0, 55.0, 50.0]))
    case("insufficient_data_two_sessions", "below the three-session minimum",
         SessionBuilder().series([40.0, 42.5]))
    case("not_applicable_cardio", "cardio: sets and reps describe no comparable workload",
         SessionBuilder().series([None] * 5), ExerciseCategory.CARDIO, "Running")

    # --- plateau guard rails ---------------------------------------------------
    case("single_session_is_not_a_plateau", "one repeated workout is insufficient data",
         SessionBuilder().series([45.0]))
    case("four_flat_sessions_in_one_week", "fails the 14-day span requirement",
         SessionBuilder().series([45.0] * 4, day_gap=2))
    case("plateau_beats_older_jump", "an old increase then four flat sessions is a plateau",
         SessionBuilder().series([40.0, 45.0, 45.0, 45.0, 45.0]))
    case("volume_moves_while_load_flat", "same load but an added set is real work",
         SessionBuilder().series([45.0] * 3).add(day_offset=21, sets=5, reps=8, weight=45.0))

    # --- boundaries ------------------------------------------------------------
    case("exactly_at_threshold", "100 -> 102 kg is exactly 2.0%, inclusive",
         SessionBuilder().series([100.0, 101.0, 102.0]))
    case("just_below_threshold", "100 -> 101.9 kg is 1.9%",
         SessionBuilder().series([100.0, 101.0, 101.9]))
    case("load_up_volume_down", "heavier but meaningfully less total work is not progress",
         SessionBuilder()
         .add(day_offset=0, sets=5, reps=10, weight=40.0)
         .add(day_offset=7, sets=4, reps=10, weight=42.5)
         .add(day_offset=14, sets=2, reps=8, weight=45.0))

    # --- rounding boundaries ---------------------------------------------------
    # Values chosen so the percentage lands on or near a .x5 tie, where CPython's
    # half-to-even differs from naive half-up rounding.
    case("rounding_tie_half_even", "percentage change lands on a rounding tie",
         SessionBuilder().series([80.0, 81.0, 82.675]))
    case("rounding_repeating_decimal", "change is a repeating decimal",
         SessionBuilder().series([30.0, 31.0, 32.0]))
    case("rounding_volume_two_dp", "volume needs two-decimal rounding",
         SessionBuilder()
         .add(day_offset=0, sets=3, reps=7, weight=12.345)
         .add(day_offset=7, sets=3, reps=7, weight=12.675)
         .add(day_offset=14, sets=3, reps=7, weight=13.005))
    case("float_weights", "42.5 repeated is a plateau despite float representation",
         SessionBuilder().series([42.5] * 4))

    # --- non-weighted and mixed ------------------------------------------------
    case("bodyweight_reps_basis", "no weight: classified on total reps",
         SessionBuilder()
         .add(day_offset=0, sets=3, reps=8, weight=None)
         .add(day_offset=7, sets=3, reps=10, weight=None)
         .add(day_offset=14, sets=3, reps=12, weight=None),
         exercise_name="Pull-up")
    case("zero_weight_is_unweighted", "weight 0 carries no load information",
         SessionBuilder().series([0.0] * 4, sets=3, reps=10), exercise_name="Push-up")
    case("mixed_weighted_and_not", "too few weighted sessions falls back to reps",
         SessionBuilder()
         .add(day_offset=0, weight=None).add(day_offset=7, weight=40.0)
         .add(day_offset=14, weight=None).add(day_offset=21, weight=42.5))
    case("mobility_not_applicable", "mobility work gets no trend",
         SessionBuilder().series([None] * 4), ExerciseCategory.MOBILITY, "Plank")

    # --- aggregation edge cases ------------------------------------------------
    case("varying_sets", "set count changes between sessions",
         SessionBuilder()
         .add(day_offset=0, sets=3, reps=8, weight=40.0)
         .add(day_offset=7, sets=5, reps=8, weight=40.0)
         .add(day_offset=14, sets=4, reps=8, weight=40.0))
    case("varying_reps", "rep count changes between sessions",
         SessionBuilder()
         .add(day_offset=0, sets=3, reps=8, weight=40.0)
         .add(day_offset=7, sets=3, reps=10, weight=40.0)
         .add(day_offset=14, sets=3, reps=12, weight=40.0))
    case("same_day_two_workouts", "two workouts on one date order by creation time",
         SessionBuilder()
         .add(day_offset=0, weight=40.0, created_offset_seconds=0)
         .add(day_offset=0, weight=45.0, created_offset_seconds=3600)
         .add(day_offset=7, weight=47.5))
    case("repeated_exercise_in_one_workout", "a drop set logged as two rows is one session",
         SessionBuilder()
         .add(day_offset=0, sets=3, reps=8, weight=40.0)
         .add(day_offset=0, sets=1, reps=5, weight=50.0, same_workout_as=1)
         .add(day_offset=7, sets=3, reps=8, weight=42.5)
         .add(day_offset=14, sets=3, reps=8, weight=45.0))
    case("window_caps_at_five_sessions", "only the most recent five sessions are analysed",
         SessionBuilder().series([20.0, 25.0, 30.0, 45.0, 45.0, 45.0, 45.0]))
    case("empty_history", "no sessions at all",
         SessionBuilder())

    # --- custom exercise and medical boundary ----------------------------------
    case("custom_exercise", "a user-created strength exercise behaves like any other",
         SessionBuilder().series([20.0, 22.5, 25.0]), exercise_name="Zercher Squat")
    case("medical_note_suppresses_suggestion",
         "a pain note withholds the prescription but keeps the verdict",
         SessionBuilder()
         .add(day_offset=0, weight=100.0)
         .add(day_offset=7, weight=100.5)
         .add(day_offset=14, weight=101.0, medical=True))


def build_unordered_case() -> dict:
    """The same history as progressing_load, shuffled — the result must be identical."""
    builder = SessionBuilder().series([40.0, 42.5, 45.0])
    shuffled = [builder.described[2], builder.described[0], builder.described[1]]
    rows = [builder.rows[2], builder.rows[0], builder.rows[1]]
    from app.modules.training_insights.schemas import ExerciseRef

    ref = ExerciseRef(id=uuid.uuid5(WORKOUT_NAMESPACE, "Bench Press"),
                      name="Bench Press", category=ExerciseCategory.STRENGTH)
    insight = analytics.build_exercise_insight(ref, rows)
    return {
        "name": "unordered_history",
        "description": "rows supplied newest-first still classify identically",
        "exercise": {"id": str(ref.id), "name": ref.name, "category": "strength"},
        "logged_sets": shuffled,
        "expected": {
            "insight": insight_to_json(insight),
            "sessions": [s.model_dump(mode="json")
                         for s in analytics.build_sessions(rows)],
            "personal_bests": analytics.find_personal_bests(
                analytics.build_sessions(rows)).model_dump(mode="json"),
        },
    }


def build_scalar_cases() -> dict:
    """Standalone numeric checks that do not need a full history."""
    return {
        "percent_change": [
            {"baseline": b, "latest": l, "expected": analytics.percent_change(b, l)}
            for b, l in [
                (40.0, 45.0), (100.0, 102.0), (100.0, 101.9), (60.0, 50.0),
                (80.0, 82.675), (30.0, 32.0), (3.0, 7.0), (7.0, 3.0),
                (0.0, 10.0), (1.0, 1.0), (12.345, 12.675), (2.675, 2.665),
            ]
        ],
        "suggest_next_load_kg": [
            {"current": c, "expected": analytics.suggest_next_load_kg(c)}
            for c in [45.0, 100.0, 200.0, 10.0, 0.0, 20.0, 12.5, 37.5, 62.5, 1.0]
        ],
        "consistency": [
            {
                "today": "2026-09-17",
                "workout_dates": dates,
                "expected": analytics.compute_consistency(
                    [date.fromisoformat(d) for d in dates],
                    today=date(2026, 9, 17)).model_dump(mode="json"),
            }
            for dates in [
                ["2026-09-17", "2026-09-15", "2026-09-12", "2026-09-09", "2026-09-06"],
                [],
                ["2026-09-20", "2026-09-17"],
                ["2026-09-17"] * 3,
                ["2026-08-01", "2026-08-15", "2026-09-01", "2026-09-17"],
            ]
        ],
        "volume_trend": [
            {
                "today": "2026-09-17",
                "entries": entries,
                "expected": analytics.compute_volume_trend(
                    [LoggedSet(
                        workout_id=workout_id(i),
                        performed_at=date.fromisoformat(e["performed_at"]),
                        workout_created_at=BASE_TIME,
                        sets=e["sets"], reps=e["reps"], weight_kg=e["weight_kg"])
                     for i, e in enumerate(entries)],
                    today=date(2026, 9, 17)).model_dump(mode="json"),
            }
            for entries in [
                [{"performed_at": "2026-09-16", "sets": 3, "reps": 10, "weight_kg": 50.0},
                 {"performed_at": "2026-09-08", "sets": 3, "reps": 10, "weight_kg": 40.0}],
                [{"performed_at": "2026-09-17", "sets": 3, "reps": 10, "weight_kg": None}],
                [{"performed_at": "2026-09-17", "sets": 3, "reps": 10, "weight_kg": 50.0}],
                [{"performed_at": "2026-09-17", "sets": 3, "reps": 7, "weight_kg": 12.345},
                 {"performed_at": "2026-09-09", "sets": 3, "reps": 7, "weight_kg": 12.675}],
            ]
        ],
    }


def main() -> None:
    build_cases()
    CASES.append(build_unordered_case())

    document = {
        "_comment": (
            "Authoritative training-insight fixtures, generated from the Python engine by "
            "contract/generate_training_fixtures.py. Consumed by both pytest and JUnit. "
            "Python's output is the source of truth — especially for rounding boundaries "
            "and evidence-string formatting."
        ),
        "base_date": BASE_DATE.isoformat(),
        "classifications": [
            "progressing", "stable", "possible_plateau",
            "declining", "insufficient_data", "not_applicable",
        ],
        "scalars": build_scalar_cases(),
        "cases": CASES,
    }

    out = ROOT / "contract" / "training-insights-fixtures.json"
    out.write_text(json.dumps(document, indent=2, default=serialise) + "\n")

    seen = {c["expected"]["insight"]["classification"] for c in CASES}
    print(f"wrote {out}")
    print(f"  cases: {len(CASES)}")
    print(f"  classifications covered: {sorted(seen)}")
    missing = set(document["classifications"]) - seen
    if missing:
        raise SystemExit(f"FAIL: classifications not covered by any case: {sorted(missing)}")
    print(f"  scalar checks: "
          f"{sum(len(v) for v in document['scalars'].values())}")


if __name__ == "__main__":
    main()
