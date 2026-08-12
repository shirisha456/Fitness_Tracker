"""Celery application for Fitness Tracker background tasks."""

from celery import Celery

from app.config import get_settings

settings = get_settings()

celery_app = Celery(
    "fitness_tracker",
    broker=settings.redis_url,
    backend=settings.redis_url,
    include=["app.modules.auth.tasks"],
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
    task_default_queue="default",
    task_routes={
        "auth.send_verification_email": {"queue": "email"},
        "auth.send_password_reset_email": {"queue": "email"},
    },
    # Eager mode for tests — set via CELERY_TASK_ALWAYS_EAGER env / settings
    task_always_eager=settings.celery_task_always_eager,
    task_eager_propagates=True,
)
