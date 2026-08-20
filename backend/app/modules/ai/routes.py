"""AI coach HTTP routes."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.dependencies import get_current_user, get_db
from app.modules.ai import service as ai_service
from app.modules.ai.schemas import ChatRequest, GenerateMealRequest, GenerateWorkoutRequest
from app.modules.auth.models import User

ai_router = APIRouter(prefix="/ai", tags=["ai"])


@ai_router.post("/generate-workout")
async def generate_workout(
    payload: GenerateWorkoutRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    workout = await ai_service.generate_workout(db, user, payload)
    return {"data": workout}


@ai_router.post("/generate-meals")
async def generate_meals(
    payload: GenerateMealRequest,
    user: User = Depends(get_current_user),
) -> dict:
    meals = await ai_service.generate_meals(payload)
    return {"data": meals}


@ai_router.post("/chat")
async def chat(
    payload: ChatRequest,
    user: User = Depends(get_current_user),
) -> dict:
    message = await ai_service.chat(payload)
    return {"data": {"message": message}}


@ai_router.get("/recommendations")
async def get_recommendations(
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> dict:
    recommendations = await ai_service.get_recommendations(db, user)
    return {"data": recommendations}
