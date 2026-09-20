"""The Python side of the shared training-insight fixtures.

The same file — contract/training-insights-fixtures.json — is consumed here and by
TrainingAnalyticsFixtureTest on the Java side. Running it against Python too is not
redundant: it guarantees the fixtures still describe the current engine, so a change to
analytics.py fails here rather than silently making the Java port "wrong" against a
stale expectation.
"""

from __future__ import annotations

import json
import pathlib
import uuid
from datetime import date, datetime

import pytest

from app.modules.training_insights import analytics
from app.modules.training_insights.analytics import LoggedSet
from app.modules.training_insights.schemas import ExerciseRef
from app.modules.workouts.models import ExerciseCategory

FIXTURES = pathlib.Path(__file__).resolve().parents[3] / "contract" / (
    "training-insights-fixtures.json"
)


def _load() -> dict:
    assert FIXTURES.exists(), (
        f"missing {FIXTURES}; run contract/generate_training_fixtures.py"
    )
    return json.loads(FIXTURES.read_text())


DOCUMENT = _load()


def _logged_sets(case: dict) -> list[LoggedSet]:
    return [
        LoggedSet(
            workout_id=uuid.UUID(row["workout_id"]),
            performed_at=date.fromisoformat(row["performed_at"]),
            workout_created_at=datetime.fromisoformat(
                row["workout_created_at"].replace("Z", "+00:00")
            ),
            sets=row["sets"],
            reps=row["reps"],
            weight_kg=row["weight_kg"],
            has_medical_note=row["has_medical_note"],
        )
        for row in case["logged_sets"]
    ]


def _exercise_ref(case: dict) -> ExerciseRef:
    return ExerciseRef(
        id=uuid.UUID(case["exercise"]["id"]),
        name=case["exercise"]["name"],
        category=ExerciseCategory(case["exercise"]["category"]),
    )


@pytest.mark.parametrize(
    "case", DOCUMENT["cases"], ids=[c["name"] for c in DOCUMENT["cases"]]
)
def test_insight_matches_fixture(case: dict) -> None:
    insight = analytics.build_exercise_insight(_exercise_ref(case), _logged_sets(case))
    actual = insight.model_dump(mode="json", by_alias=True)

    assert actual == case["expected"]["insight"], (
        f"{case['name']}: the engine no longer produces the recorded result. "
        f"If the change was intended, regenerate the fixtures."
    )


@pytest.mark.parametrize(
    "case", DOCUMENT["cases"], ids=[c["name"] for c in DOCUMENT["cases"]]
)
def test_sessions_and_bests_match_fixture(case: dict) -> None:
    sessions = analytics.build_sessions(_logged_sets(case))
    assert [s.model_dump(mode="json") for s in sessions] == case["expected"]["sessions"]

    bests = analytics.find_personal_bests(sessions)
    assert bests.model_dump(mode="json") == case["expected"]["personal_bests"]


def test_every_classification_is_covered() -> None:
    covered = {c["expected"]["insight"]["classification"] for c in DOCUMENT["cases"]}
    assert covered == set(DOCUMENT["classifications"]), (
        "all six classifications must appear in the shared fixtures"
    )


@pytest.mark.parametrize("scalar", DOCUMENT["scalars"]["percent_change"])
def test_percent_change_matches_fixture(scalar: dict) -> None:
    assert analytics.percent_change(scalar["baseline"], scalar["latest"]) == scalar["expected"]


@pytest.mark.parametrize("scalar", DOCUMENT["scalars"]["suggest_next_load_kg"])
def test_suggested_load_matches_fixture(scalar: dict) -> None:
    assert analytics.suggest_next_load_kg(scalar["current"]) == scalar["expected"]


@pytest.mark.parametrize("scalar", DOCUMENT["scalars"]["consistency"])
def test_consistency_matches_fixture(scalar: dict) -> None:
    metrics = analytics.compute_consistency(
        [date.fromisoformat(d) for d in scalar["workout_dates"]],
        today=date.fromisoformat(scalar["today"]),
    )
    assert metrics.model_dump(mode="json") == scalar["expected"]


@pytest.mark.parametrize("scalar", DOCUMENT["scalars"]["volume_trend"])
def test_volume_trend_matches_fixture(scalar: dict) -> None:
    entries = [
        LoggedSet(
            workout_id=uuid.uuid4(),
            performed_at=date.fromisoformat(entry["performed_at"]),
            workout_created_at=datetime(2026, 6, 1, 8, 0),
            sets=entry["sets"],
            reps=entry["reps"],
            weight_kg=entry["weight_kg"],
        )
        for entry in scalar["entries"]
    ]
    trend = analytics.compute_volume_trend(
        entries, today=date.fromisoformat(scalar["today"])
    )
    assert trend.model_dump(mode="json") == scalar["expected"]
