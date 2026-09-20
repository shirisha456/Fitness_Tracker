"""Training-insight HTTP routes.

`explain` defaults to false on every endpoint. Analytics are computed from the
database alone, so these endpoints keep working unchanged when OpenAI is not
configured or is unavailable.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.dependencies import get_current_user, get_db
from app.modules.auth.models import User
from app.modules.training_insights import service as insights_service
from app.modules.training_insights.rules import HISTORY_MAX_SESSIONS

training_router = APIRouter(prefix="/training", tags=["training-insights"])


@training_router.get("/overview")
async def get_overview(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    overview = await insights_service.get_overview(db, user)
    return {"data": overview}


@training_router.get("/recommendations")
async def get_recommendations(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    recommendations = await insights_service.get_recommendations(db, user)
    return {"data": recommendations}


@training_router.get("/exercises/{exercise_id}/history")
async def get_exercise_history(
    exercise_id: uuid.UUID,
    limit: int = Query(default=HISTORY_MAX_SESSIONS, ge=1, le=HISTORY_MAX_SESSIONS),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    history = await insights_service.get_exercise_history(db, user, exercise_id, limit=limit)
    return {"data": history}


@training_router.get("/exercises/{exercise_id}/insights")
async def get_exercise_insight(
    exercise_id: uuid.UUID,
    explain: bool = Query(
        default=False,
        description=(
            "Ask the AI coach to reword the deterministic explanation. The metrics "
            "and classification are identical either way; on any AI failure the "
            "response falls back to the deterministic text."
        ),
    ),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    insight = await insights_service.get_exercise_insight(
        db, user, exercise_id, explain=explain
    )
    return {"data": insight}
