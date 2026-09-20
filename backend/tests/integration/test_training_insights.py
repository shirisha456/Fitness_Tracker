"""Integration tests for training insights, against a real PostgreSQL database.

Workouts are created through the real HTTP API rather than inserted directly, so
these exercise the same path a user does. OpenAI is always mocked — see
test_ai.py for the same approach.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import openai
import pytest

from app.modules.ai import service as ai_service
from app.modules.workouts.models import Exercise, ExerciseCategory

TODAY = datetime.now(UTC).date()


@pytest.fixture
async def exercises(db_session):
    bench = Exercise(
        name="Bench Press",
        category=ExerciseCategory.STRENGTH,
        muscle_group="chest",
        equipment="barbell",
    )
    squat = Exercise(name="Squat", category=ExerciseCategory.STRENGTH, muscle_group="legs")
    pullup = Exercise(
        name="Pull-up", category=ExerciseCategory.STRENGTH, equipment="bodyweight"
    )
    running = Exercise(name="Running", category=ExerciseCategory.CARDIO)
    db_session.add_all([bench, squat, pullup, running])
    await db_session.flush()
    await db_session.commit()
    return {"bench": bench, "squat": squat, "pullup": pullup, "running": running}


async def _auth_headers(client, email: str, password: str = "SecurePass123!") -> dict[str, str]:
    payload = {"email": email, "password": password, "password_confirm": password}
    await client.post("/api/v1/auth/register", json=payload)
    login = await client.post("/api/v1/auth/login", json={"email": email, "password": password})
    return {"Authorization": f"Bearer {login.json()['data']['access_token']}"}


async def _log_session(
    client,
    headers,
    exercise,
    *,
    days_ago: int,
    sets: int = 3,
    reps: int = 8,
    weight: float | None = 40.0,
    notes: str | None = None,
) -> None:
    performed_at = (TODAY - timedelta(days=days_ago)).isoformat()
    entry: dict = {"exercise_id": str(exercise.id), "sets": sets, "reps": reps}
    if weight is not None:
        entry["weight_kg"] = weight
    if notes is not None:
        entry["notes"] = notes
    response = await client.post(
        "/api/v1/workouts",
        json={"name": "Session", "performed_at": performed_at, "exercises": [entry]},
        headers=headers,
    )
    assert response.status_code == 201, response.text


def _insight(client_response) -> dict:
    assert client_response.status_code == 200, client_response.text
    return client_response.json()["data"]


def _fake_completion(content: str):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])


def _mock_openai_client(monkeypatch, content: str):
    fake = SimpleNamespace(
        chat=SimpleNamespace(
            completions=SimpleNamespace(create=AsyncMock(return_value=_fake_completion(content)))
        )
    )
    monkeypatch.setattr(ai_service, "_client", lambda: fake)
    return fake


def _mock_openai_failure(monkeypatch, exc: Exception):
    fake = SimpleNamespace(
        chat=SimpleNamespace(
            completions=SimpleNamespace(create=AsyncMock(side_effect=exc))
        )
    )
    monkeypatch.setattr(ai_service, "_client", lambda: fake)


# --- Auth and ownership --------------------------------------------------------


@pytest.mark.asyncio
async def test_endpoints_require_auth(client, exercises):
    for path in (
        "/api/v1/training/overview",
        "/api/v1/training/recommendations",
        f"/api/v1/training/exercises/{exercises['bench'].id}/insights",
        f"/api/v1/training/exercises/{exercises['bench'].id}/history",
    ):
        response = await client.get(path)
        assert response.status_code == 401, path


@pytest.mark.asyncio
async def test_one_users_history_never_reaches_another(client, exercises):
    alice = await _auth_headers(client, "alice-insights@example.com")
    bob = await _auth_headers(client, "bob-insights@example.com")

    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, alice, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )

    alice_insight = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights", headers=alice
        )
    )
    assert alice_insight["classification"] == "progressing"
    assert alice_insight["sessions_analyzed"] == 3

    # Same exercise id, different user: no sessions, no leaked numbers.
    bob_insight = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights", headers=bob
        )
    )
    assert bob_insight["classification"] == "insufficient_data"
    assert bob_insight["sessions_analyzed"] == 0
    assert bob_insight["current_weight_kg"] is None

    bob_history = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/history", headers=bob
        )
    )
    assert bob_history["sessions"] == []
    assert bob_history["personal_bests"]["heaviest_weight_kg"] is None

    bob_overview = _insight(await client.get("/api/v1/training/overview", headers=bob))
    assert bob_overview["exercises"] == []
    assert bob_overview["consistency"]["workouts_last_7_days"] == 0


@pytest.mark.asyncio
async def test_unknown_exercise_is_404(client, exercises):
    headers = await _auth_headers(client, "unknown-ex@example.com")
    missing = "00000000-0000-0000-0000-000000000000"
    response = await client.get(
        f"/api/v1/training/exercises/{missing}/insights", headers=headers
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "NOT_FOUND"


# --- Deterministic results end to end ------------------------------------------


@pytest.mark.asyncio
async def test_progressing_history_is_classified_progressing(client, exercises):
    headers = await _auth_headers(client, "progress-insights@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights", headers=headers
        )
    )
    assert data["classification"] == "progressing"
    assert data["metric_basis"] == "load"
    assert data["current_weight_kg"] == 45.0
    assert data["previous_weight_kg"] == 42.5
    assert data["primary_change_percent"] == 12.5
    assert data["plateau_detected"] is False
    assert data["suggestion"]["kind"] == "maintain"
    assert data["explanation_source"] == "deterministic"
    assert len(data["evidence"]) >= 2
    assert data["date_range"]["from"] == (TODAY - timedelta(days=21)).isoformat()
    assert data["date_range"]["to"] == (TODAY - timedelta(days=7)).isoformat()


@pytest.mark.asyncio
async def test_four_flat_sessions_are_classified_possible_plateau(client, exercises):
    headers = await _auth_headers(client, "plateau-insights@example.com")
    for days_ago in (28, 21, 14, 7):
        await _log_session(client, headers, exercises["squat"], days_ago=days_ago, weight=60.0)

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['squat'].id}/insights", headers=headers
        )
    )
    assert data["classification"] == "possible_plateau"
    assert data["plateau_detected"] is True
    assert data["sessions_analyzed"] == 4
    assert data["suggestion"]["kind"] == "review_exercise"
    assert data["suggestion"]["suggested_load_kg"] is None


@pytest.mark.asyncio
async def test_cardio_is_reported_as_not_applicable(client, exercises):
    headers = await _auth_headers(client, "cardio-insights@example.com")
    for days_ago in (21, 14, 7, 1):
        await _log_session(
            client, headers, exercises["running"], days_ago=days_ago, sets=1, reps=1, weight=None
        )

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['running'].id}/insights", headers=headers
        )
    )
    assert data["classification"] == "not_applicable"
    assert data["metric_basis"] == "none"


@pytest.mark.asyncio
async def test_bodyweight_exercise_uses_a_reps_basis(client, exercises):
    headers = await _auth_headers(client, "bodyweight-insights@example.com")
    for index, reps in enumerate([5, 7, 9]):
        await _log_session(
            client,
            headers,
            exercises["pullup"],
            days_ago=21 - index * 7,
            reps=reps,
            weight=None,
        )

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['pullup'].id}/insights", headers=headers
        )
    )
    assert data["metric_basis"] == "reps"
    assert data["classification"] == "progressing"
    assert data["current_weight_kg"] is None
    assert data["volume_change_percent"] is None


@pytest.mark.asyncio
async def test_future_dated_workouts_are_excluded(client, exercises):
    headers = await _auth_headers(client, "future-insights@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )
    # A workout dated ahead of today is not something that happened.
    await _log_session(client, headers, exercises["bench"], days_ago=-7, weight=100.0)

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights", headers=headers
        )
    )
    assert data["current_weight_kg"] == 45.0
    assert data["sessions_analyzed"] == 3


# --- History and personal bests ------------------------------------------------


@pytest.mark.asyncio
async def test_history_returns_a_session_table_and_records(client, exercises):
    headers = await _auth_headers(client, "history-insights@example.com")
    await _log_session(client, headers, exercises["bench"], days_ago=16, weight=40.0)
    await _log_session(client, headers, exercises["bench"], days_ago=12, weight=42.5)
    await _log_session(
        client, headers, exercises["bench"], days_ago=8, sets=5, reps=8, weight=45.0
    )

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/history", headers=headers
        )
    )
    sessions = data["sessions"]
    assert [s["top_weight_kg"] for s in sessions] == [40.0, 42.5, 45.0]
    assert sessions[0]["total_sets"] == 3
    assert sessions[0]["reps_per_set"] == 8
    assert sessions[0]["total_reps"] == 24
    assert sessions[0]["volume_kg"] == 960.0
    assert sessions[2]["volume_kg"] == 1800.0

    bests = data["personal_bests"]
    assert bests["heaviest_weight_kg"] == 45.0
    assert bests["best_session_volume_kg"] == 1800.0
    assert bests["most_reps_at_heaviest_weight"] == 40


@pytest.mark.asyncio
async def test_history_limit_trims_the_table_without_losing_records(client, exercises):
    headers = await _auth_headers(client, "history-limit@example.com")
    await _log_session(
        client, headers, exercises["bench"], days_ago=30, sets=5, reps=8, weight=80.0
    )
    await _log_session(client, headers, exercises["bench"], days_ago=10, weight=40.0)
    await _log_session(client, headers, exercises["bench"], days_ago=5, weight=42.5)

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/history?limit=2",
            headers=headers,
        )
    )
    assert len(data["sessions"]) == 2
    # The record set 30 days ago is outside the trimmed table but still reported.
    assert data["personal_bests"]["heaviest_weight_kg"] == 80.0


# --- Overview, consistency and workload ----------------------------------------


@pytest.mark.asyncio
async def test_overview_reports_consistency_and_volume(client, exercises):
    headers = await _auth_headers(client, "overview-insights@example.com")
    # Three sessions this week, two last week.
    for days_ago in (1, 3, 5):
        await _log_session(
            client, headers, exercises["bench"], days_ago=days_ago, sets=3, reps=10, weight=50.0
        )
    for days_ago in (8, 10):
        await _log_session(
            client, headers, exercises["bench"], days_ago=days_ago, sets=3, reps=10, weight=40.0
        )

    data = _insight(await client.get("/api/v1/training/overview", headers=headers))

    assert data["consistency"]["workouts_last_7_days"] == 3
    assert data["consistency"]["workouts_previous_7_days"] == 2
    assert data["consistency"]["change"] == 1
    assert data["volume"]["current_7_day_volume_kg"] == 4500.0   # 3 x (3x10x50)
    assert data["volume"]["previous_7_day_volume_kg"] == 2400.0  # 2 x (3x10x40)
    assert data["volume"]["change_percent"] == 87.5
    assert len(data["exercises"]) == 1
    assert data["exercises"][0]["exercise"]["name"] == "Bench Press"
    assert data["generated_on"] == TODAY.isoformat()


@pytest.mark.asyncio
async def test_overview_is_empty_but_valid_for_a_new_user(client, exercises):
    headers = await _auth_headers(client, "empty-overview@example.com")
    data = _insight(await client.get("/api/v1/training/overview", headers=headers))

    assert data["exercises"] == []
    assert data["consistency"]["workouts_last_7_days"] == 0
    assert data["volume"]["current_7_day_volume_kg"] == 0.0
    assert data["volume"]["change_percent"] is None


@pytest.mark.asyncio
async def test_recommendations_only_surface_actionable_suggestions(client, exercises):
    headers = await _auth_headers(client, "recs-insights@example.com")
    # A plateaued squat is actionable; a progressing bench is not.
    for days_ago in (28, 21, 14, 7):
        await _log_session(client, headers, exercises["squat"], days_ago=days_ago, weight=60.0)
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )

    data = _insight(await client.get("/api/v1/training/recommendations", headers=headers))
    names = {r["exercise"]["name"] for r in data["recommendations"]}

    assert "Squat" in names
    assert "Bench Press" not in names
    squat_rec = next(r for r in data["recommendations"] if r["exercise"]["name"] == "Squat")
    assert squat_rec["classification"] == "possible_plateau"
    assert squat_rec["evidence"]


# --- Medical boundary ----------------------------------------------------------


@pytest.mark.asyncio
async def test_a_pain_note_replaces_the_training_suggestion_with_a_referral(
    client, exercises
):
    headers = await _auth_headers(client, "pain-insights@example.com")
    await _log_session(client, headers, exercises["bench"], days_ago=21, weight=100.0)
    await _log_session(client, headers, exercises["bench"], days_ago=14, weight=100.5)
    await _log_session(
        client,
        headers,
        exercises["bench"],
        days_ago=7,
        weight=101.0,
        notes="Sharp pain in my left shoulder",
    )

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights", headers=headers
        )
    )
    assert data["suggestion"]["kind"] == "consult_professional"
    assert data["suggestion"]["suggested_load_kg"] is None
    assert "qualified professional" in data["suggestion"]["message"]
    # The note text itself is never echoed back in the response.
    assert "shoulder" not in str(data)


# --- AI explanation layer ------------------------------------------------------


@pytest.mark.asyncio
async def test_explanation_is_deterministic_unless_explicitly_requested(client, exercises):
    headers = await _auth_headers(client, "explain-off@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights", headers=headers
        )
    )
    assert data["explanation_source"] == "deterministic"
    assert "Bench Press" in data["explanation"]


@pytest.mark.asyncio
async def test_ai_explanation_rewords_without_changing_the_numbers(
    client, exercises, monkeypatch
):
    headers = await _auth_headers(client, "explain-ai@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )
    _mock_openai_client(monkeypatch, "Nice work — your bench has climbed steadily.")

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights?explain=true",
            headers=headers,
        )
    )
    assert data["explanation_source"] == "ai"
    assert data["explanation"] == "Nice work — your bench has climbed steadily."
    # The deterministic half is untouched by the AI call.
    assert data["classification"] == "progressing"
    assert data["current_weight_kg"] == 45.0
    assert data["primary_change_percent"] == 12.5


@pytest.mark.asyncio
async def test_prompt_contains_computed_metrics_not_raw_history(client, exercises, monkeypatch):
    headers = await _auth_headers(client, "explain-prompt@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client,
            headers,
            exercises["bench"],
            days_ago=21 - index * 7,
            weight=weight,
            notes="Felt heavy",
        )
    fake = _mock_openai_client(monkeypatch, "Steady progress.")

    await client.get(
        f"/api/v1/training/exercises/{exercises['bench'].id}/insights?explain=true",
        headers=headers,
    )

    sent = fake.chat.completions.create.await_args.kwargs["messages"]
    user_message = sent[1]["content"]
    assert "Classification: progressing" in user_message
    assert "Sessions analyzed: 3" in user_message
    assert "Felt heavy" not in user_message           # notes never reach the model
    assert "do not recalculate" not in user_message   # that instruction is in the system prompt
    assert "Do not calculate anything." in sent[0]["content"]


@pytest.mark.asyncio
async def test_provider_failure_falls_back_to_deterministic_text(
    client, exercises, monkeypatch
):
    headers = await _auth_headers(client, "explain-fail@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )
    _mock_openai_failure(
        monkeypatch,
        openai.APIConnectionError(request=httpx.Request("POST", "https://api.openai.com")),
    )

    response = await client.get(
        f"/api/v1/training/exercises/{exercises['bench'].id}/insights?explain=true",
        headers=headers,
    )
    # The endpoint still succeeds: analytics do not depend on the AI provider.
    data = _insight(response)
    assert data["explanation_source"] == "deterministic"
    assert data["classification"] == "progressing"
    assert data["explanation"].strip()


@pytest.mark.asyncio
async def test_missing_api_key_falls_back_to_deterministic_text(client, exercises, monkeypatch):
    from app.config import get_settings

    headers = await _auth_headers(client, "explain-nokey@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )
    monkeypatch.setattr(get_settings(), "openai_api_key", None)

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights?explain=true",
            headers=headers,
        )
    )
    assert data["explanation_source"] == "deterministic"
    assert data["classification"] == "progressing"


@pytest.mark.asyncio
async def test_empty_ai_response_keeps_the_deterministic_text(client, exercises, monkeypatch):
    headers = await _auth_headers(client, "explain-empty@example.com")
    for index, weight in enumerate([40.0, 42.5, 45.0]):
        await _log_session(
            client, headers, exercises["bench"], days_ago=21 - index * 7, weight=weight
        )
    _mock_openai_client(monkeypatch, "   ")

    data = _insight(
        await client.get(
            f"/api/v1/training/exercises/{exercises['bench'].id}/insights?explain=true",
            headers=headers,
        )
    )
    assert data["explanation_source"] == "deterministic"
    assert data["explanation"].strip()


# --- The existing AI coach keeps working, with better input --------------------


@pytest.mark.asyncio
async def test_coach_recommendations_receive_deterministic_training_signals(
    client, exercises, monkeypatch
):
    import json

    headers = await _auth_headers(client, "coach-signals@example.com")
    for days_ago in (28, 21, 14, 7):
        await _log_session(client, headers, exercises["squat"], days_ago=days_ago, weight=60.0)
    fake = _mock_openai_client(monkeypatch, json.dumps({"recommendations": ["Keep going."]}))

    response = await client.get("/api/v1/ai/recommendations", headers=headers)
    assert response.status_code == 200
    assert response.json()["data"]["recommendations"] == ["Keep going."]

    prompt = fake.chat.completions.create.await_args.kwargs["messages"][1]["content"]
    assert "Squat: possible_plateau" in prompt
    assert "Training frequency:" in prompt


@pytest.mark.asyncio
async def test_coach_signals_are_honest_when_there_is_no_history(
    client, exercises, monkeypatch
):
    import json

    headers = await _auth_headers(client, "coach-nohistory@example.com")
    fake = _mock_openai_client(monkeypatch, json.dumps({"recommendations": ["Log a workout."]}))

    response = await client.get("/api/v1/ai/recommendations", headers=headers)
    assert response.status_code == 200

    prompt = fake.chat.completions.create.await_args.kwargs["messages"][1]["content"]
    assert "No exercise has enough comparable history" in prompt
