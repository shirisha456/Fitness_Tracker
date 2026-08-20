"""AI coach business logic: workout/meal generation, chat, recommendations."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import openai
from openai import AsyncOpenAI
from pydantic import BaseModel, ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.core.exceptions import AppException
from app.modules.ai.schemas import (
    ChatRequest,
    GeneratedExercise,
    GeneratedWorkoutResponse,
    GenerateMealRequest,
    GenerateMealResponse,
    GenerateWorkoutRequest,
    LlmWorkout,
    RecommendationsResponse,
)
from app.modules.auth.models import User
from app.modules.nutrition import service as nutrition_service
from app.modules.progress import service as progress_service
from app.modules.workouts import service as workouts_service

_COACH_SYSTEM_PROMPT = (
    "You are Fitness Tracker's AI fitness coach. Give practical, encouraging, concise advice about "
    "workouts, nutrition, and general fitness. You are not a medical professional: for "
    "injuries, pain, or medical conditions, advise the user to consult a doctor. Keep "
    "responses short and actionable."
)


# Cached per API key. Constructing AsyncOpenAI per request threw away its connection
# pool every time; the key is part of the cache key so a rotated key still takes effect.
_cached_client: tuple[str, AsyncOpenAI] | None = None


def _client() -> AsyncOpenAI:
    global _cached_client
    settings = get_settings()
    if not settings.openai_api_key:
        raise AppException(
            code="SERVICE_UNAVAILABLE",
            message="AI features are not configured.",
            status_code=503,
        )
    if _cached_client is None or _cached_client[0] != settings.openai_api_key:
        _cached_client = (
            settings.openai_api_key,
            AsyncOpenAI(
                api_key=settings.openai_api_key,
                timeout=settings.openai_timeout_seconds,
                max_retries=1,
            ),
        )
    return _cached_client[1]


def _validate_llm_payload[T: BaseModel](model: type[T], raw: object) -> T:
    """Validate a decoded LLM payload, translating a bad shape into a 502.

    JSON mode constrains the model to *parseable* JSON, not to our schema — and a
    response truncated at max_tokens isn't even parseable. Both used to escape as
    an unhandled exception and surface as a generic 500; they're upstream failures,
    so they belong with the other OpenAI errors as a 502.
    """
    try:
        return model.model_validate(raw)
    except ValidationError as exc:
        raise AppException(
            code="BAD_GATEWAY",
            message="AI service returned an unexpected response.",
            status_code=502,
        ) from exc


def _translate_openai_error(exc: Exception) -> AppException:
    if isinstance(exc, openai.AuthenticationError):
        return AppException(
            code="SERVICE_UNAVAILABLE", message="AI service is misconfigured.", status_code=503
        )
    if isinstance(exc, openai.RateLimitError):
        return AppException(
            code="SERVICE_UNAVAILABLE",
            message="AI service is temporarily unavailable (rate limit or quota exceeded).",
            status_code=503,
        )
    return AppException(
        code="BAD_GATEWAY", message="AI service request failed.", status_code=502
    )


async def _complete_json(system_prompt: str, user_prompt: str, *, max_tokens: int) -> dict:
    settings = get_settings()
    client = _client()
    try:
        completion = await client.chat.completions.create(
            model=settings.openai_model,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            response_format={"type": "json_object"},
            max_tokens=max_tokens,
        )
    except openai.APIError as exc:
        raise _translate_openai_error(exc) from exc

    content = completion.choices[0].message.content
    try:
        return json.loads(content or "{}")
    except json.JSONDecodeError as exc:
        raise AppException(
            code="BAD_GATEWAY",
            message="AI service returned a malformed response.",
            status_code=502,
        ) from exc


async def _complete_text(messages: list[dict], *, max_tokens: int) -> str:
    settings = get_settings()
    client = _client()
    try:
        completion = await client.chat.completions.create(
            model=settings.openai_model,
            messages=messages,
            max_tokens=max_tokens,
        )
    except openai.APIError as exc:
        raise _translate_openai_error(exc) from exc

    return completion.choices[0].message.content or ""


async def generate_workout(
    db: AsyncSession, user: User, payload: GenerateWorkoutRequest
) -> GeneratedWorkoutResponse:
    # Prompt-visible exercises are scoped to the curated library plus this user's own
    # custom entries. Exercise names are free text written by users, and they are
    # concatenated verbatim into the system prompt below — an unscoped list would let
    # any user inject instructions into every other user's prompt.
    exercises = await workouts_service.list_exercises_for_prompt(db, user)
    name_to_id = {e.name.lower(): e.id for e in exercises}
    exercise_names = ", ".join(e.name for e in exercises)

    system_prompt = (
        "You are a fitness coach creating a workout plan for the Fitness Tracker app. "
        f"You must only use exercises from this exact list: {exercise_names}. "
        "Respond with JSON matching this shape: "
        '{"name": string, "exercises": [{"exercise_name": string, "sets": int, '
        '"reps": int, "notes": string|null}]}'
    )
    user_prompt = (
        f"Goal: {payload.goal}\n"
        f"Available equipment: {payload.equipment or 'any'}\n"
        f"Session length: {payload.duration_minutes} minutes\n"
        f"Difficulty: {payload.difficulty}"
    )

    raw = await _complete_json(system_prompt, user_prompt, max_tokens=800)
    llm_workout = _validate_llm_payload(LlmWorkout, raw)

    resolved = [
        GeneratedExercise(
            exercise_name=item.exercise_name,
            exercise_id=name_to_id.get(item.exercise_name.lower()),
            sets=item.sets,
            reps=item.reps,
            notes=item.notes,
        )
        for item in llm_workout.exercises
    ]
    return GeneratedWorkoutResponse(name=llm_workout.name, exercises=resolved)


async def generate_meals(payload: GenerateMealRequest) -> GenerateMealResponse:
    system_prompt = (
        "You are a nutrition assistant for the Fitness Tracker app. "
        "Suggest 3 realistic meal ideas. "
        'Respond with JSON matching this shape: {"suggestions": [{"name": string, '
        '"estimated_calories": int, "protein_g": number, "carbs_g": number, "fat_g": number}]}'
    )
    user_prompt = (
        f"Meal type: {payload.meal_type}\n"
        f"Dietary restrictions: {payload.dietary_restrictions or 'none'}\n"
        f"Target calories: {payload.target_calories or 'no specific target'}"
    )

    raw = await _complete_json(system_prompt, user_prompt, max_tokens=600)
    return _validate_llm_payload(GenerateMealResponse, raw)


async def chat(payload: ChatRequest) -> str:
    messages = [{"role": "system", "content": _COACH_SYSTEM_PROMPT}] + [
        {"role": m.role, "content": m.content} for m in payload.messages
    ]
    return await _complete_text(messages, max_tokens=400)


async def get_recommendations(db: AsyncSession, user: User) -> RecommendationsResponse:
    today = datetime.now(UTC).date()
    week_ago = today - timedelta(days=7)

    recent_workouts = await workouts_service.list_workouts(
        db, user, date_from=week_ago, date_to=today
    )
    nutrition_summary = await nutrition_service.get_daily_summary(db, user, today)
    measurements = await progress_service.list_measurements(db, user, date_to=today)

    context_lines = [
        f"Workouts logged in the last 7 days: {len(recent_workouts)}",
        f"Calories logged today: {nutrition_summary.total_calories}",
    ]
    if measurements:
        latest = measurements[0]
        context_lines.append(f"Latest weight: {latest.weight_kg}kg on {latest.recorded_at}")
        if len(measurements) > 1:
            previous = measurements[1]
            context_lines.append(
                f"Previous weight: {previous.weight_kg}kg on {previous.recorded_at}"
            )
    else:
        context_lines.append("No body measurements logged yet.")

    system_prompt = (
        "You are Fitness Tracker's AI fitness coach. Based on the user's recent activity, give 2-3 "
        "short, specific, encouraging tips (one sentence each). "
        'Respond with JSON matching this shape: {"recommendations": [string, ...]}'
    )
    raw = await _complete_json(system_prompt, "\n".join(context_lines), max_tokens=300)
    return _validate_llm_payload(RecommendationsResponse, raw)
