from functools import lru_cache
from typing import Annotated, Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    model_config = SettingsConfigDict(
        # Prefer process env (Docker Compose / pytest). Optional dotenv for local runs.
        env_file=".env",
        env_file_encoding="utf-8",
        env_ignore_empty=True,
        case_sensitive=False,
        extra="ignore",
    )

    environment: Literal["development", "staging", "production", "test"] = "development"
    secret_key: str = Field(..., min_length=16)
    database_url: str
    redis_url: str
    # NoDecode: pydantic-settings must NOT JSON-decode this. Comma-separated env values
    # like CORS_ORIGINS=http://localhost:3000,http://localhost are parsed in the validator.
    cors_origins: Annotated[list[str], NoDecode] = Field(
        default_factory=lambda: ["http://localhost:3000"]
    )
    openai_api_key: str | None = None
    openai_model: str = "gpt-4o-mini"

    # JWT / Auth
    access_token_expire_minutes: int = Field(default=15, ge=1)
    refresh_token_expire_days: int = Field(default=7, ge=1)
    jwt_algorithm: str = "HS256"

    # Email
    email_backend: Literal["smtp", "console"] = "smtp"
    smtp_host: str = "localhost"
    smtp_port: int = 1025
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = "Fitness Tracker <noreply@fitness-tracker.local>"
    smtp_use_tls: bool = False
    frontend_url: str = "http://localhost:3000"
    email_verification_expire_hours: int = Field(default=24, ge=1)
    password_reset_expire_hours: int = Field(default=1, ge=1)

    # Celery
    celery_task_always_eager: bool = False

    @field_validator("cors_origins", mode="before")
    @classmethod
    def parse_cors_origins(cls, value: object) -> list[str]:
        if value is None or value == "":
            return ["http://localhost:3000"]
        if isinstance(value, str):
            # Support comma-separated and accidental JSON-array strings.
            stripped = value.strip()
            if stripped.startswith("[") and stripped.endswith("]"):
                inner = stripped[1:-1].strip()
                if not inner:
                    return []
                return [
                    part.strip().strip('"').strip("'")
                    for part in inner.split(",")
                    if part.strip()
                ]
            return [origin.strip() for origin in stripped.split(",") if origin.strip()]
        if isinstance(value, (list, tuple)):
            return [str(origin).strip() for origin in value if str(origin).strip()]
        raise TypeError("cors_origins must be a string or list of strings")

    @field_validator("openai_api_key", mode="before")
    @classmethod
    def empty_openai_key_as_none(cls, value: object) -> object:
        if value == "":
            return None
        return value

    @property
    def is_production(self) -> bool:
        return self.environment == "production"


@lru_cache
def get_settings() -> Settings:
    return Settings()
