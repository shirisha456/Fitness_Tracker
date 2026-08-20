"""Pytest fixtures for Fitness Tracker backend tests."""

from __future__ import annotations

import os

# Must run before any app imports so Settings / engine see test values.
#
# setdefault, not assignment: the db_engine fixture runs drop_all/create_all, so
# hard-coding localhost:5432 pointed the suite at whatever happened to hold that port
# — including another project's database. Respecting an already-set DATABASE_URL lets
# CI and a developer with a port conflict aim the suite somewhere safe.
os.environ.setdefault("SECRET_KEY", "test-secret-key-for-pytest-only")
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://fitforge:fitforge@localhost:5432/fitforge"
)
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ["ENVIRONMENT"] = "test"
os.environ["EMAIL_BACKEND"] = "console"
os.environ["CELERY_TASK_ALWAYS_EAGER"] = "true"
os.environ.setdefault("FRONTEND_URL", "http://localhost:3000")
os.environ.setdefault("CORS_ORIGINS", "http://localhost:3000,http://localhost")

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Ensure all models are registered on Base.metadata before create_all runs
import app.modules.auth.models  # noqa: F401
import app.modules.nutrition.models  # noqa: F401
import app.modules.profile.models  # noqa: F401
import app.modules.progress.models  # noqa: F401
import app.modules.workouts.models  # noqa: F401
from app.config import get_settings
from app.core.database import Base
from app.core.email import clear_sent_emails
from app.dependencies import get_db
from app.main import create_app

get_settings.cache_clear()


@pytest.fixture(scope="session")
def anyio_backend():
    return "asyncio"


@pytest.fixture
def app():
    get_settings.cache_clear()
    return create_app()


@pytest.fixture(autouse=True)
def _clear_emails():
    clear_sent_emails()
    yield
    clear_sent_emails()


@pytest.fixture
async def db_engine():
    get_settings.cache_clear()
    settings = get_settings()
    engine = create_async_engine(settings.database_url, echo=False)
    async with engine.begin() as conn:
        await conn.execute(text("CREATE EXTENSION IF NOT EXISTS pgcrypto"))
        await conn.run_sync(Base.metadata.drop_all)
        await conn.execute(text("DROP TYPE IF EXISTS user_role CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS exercise_category CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS goal_status CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS profile_sex CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS profile_activity_level CASCADE"))
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.execute(text("DROP TYPE IF EXISTS user_role CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS exercise_category CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS goal_status CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS profile_sex CASCADE"))
        await conn.execute(text("DROP TYPE IF EXISTS profile_activity_level CASCADE"))
    await engine.dispose()


@pytest.fixture
async def db_session(db_engine):
    session_factory = async_sessionmaker(
        db_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
        autocommit=False,
    )
    async with session_factory() as session:
        yield session
        await session.rollback()


@pytest.fixture
async def client(app, db_engine):
    session_factory = async_sessionmaker(
        db_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
        autocommit=False,
    )

    async def override_get_db():
        async with session_factory() as session:
            try:
                yield session
                await session.commit()
            except Exception:
                await session.rollback()
                raise

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
    app.dependency_overrides.clear()


@pytest.fixture
def register_payload():
    return {
        "email": "alex@example.com",
        "password": "SecurePass123!",
        "password_confirm": "SecurePass123!",
    }
