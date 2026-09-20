# Phase 1 — Foundation & Auth

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


FastAPI application skeleton plus the complete `auth` module: registration, login, JWT
access/refresh tokens, email verification, and password reset.

## What was built

- **Config** (`app/config.py`) — `pydantic-settings`-based `Settings`, loaded from environment
  variables with an optional `.env` for local runs. `CORS_ORIGINS` accepts both comma-separated
  and accidental JSON-array strings via a custom validator.
- **Database** (`app/core/database.py`) — async SQLAlchemy engine + `async_sessionmaker`
  against `postgresql+asyncpg://`.
- **Security** (`app/core/security.py`) — Argon2id password hashing, JWT access (15 min) and
  refresh (7 day) token creation/decoding, SHA-256 refresh-token hashing for storage.
- **Email** (`app/core/email.py`) — pluggable `smtp` (Mailhog locally, real SMTP in prod) or
  `console` (in-memory capture for tests) backend.
- **Exceptions & middleware** — a structured `{"error": {code, message, details,
  correlation_id}}` envelope for every error response, plus a correlation-ID middleware that
  propagates `X-Correlation-ID` across the request lifecycle.
- **`auth` module** — `User`/`RefreshToken`/`EmailVerificationToken`/`PasswordResetToken`
  models, register/login/refresh/logout/verify-email/resend-verification/forgot-password/
  reset-password routes, and Celery tasks that queue the verification and reset emails.
- **Migrations** 001 (`users`, `refresh_tokens`) and 002 (`email_verification_tokens`,
  `password_reset_tokens`).

## Notable design points

- **Refresh token rotation with reuse detection.** Every refresh both issues a new token pair
  and revokes the old one. If a *revoked* token is presented again — the signature of a stolen
  token being replayed — every active session for that user is revoked immediately. See
  [ADR 001](adr/001-jwt-refresh-rotation.md).
- **Timing-safe login.** A precomputed dummy Argon2 hash is always verified against, even when
  the email doesn't exist, so failed-login response time doesn't leak whether an account exists.
- **No email enumeration.** `forgot-password` always returns the same generic success message
  regardless of whether the email is registered.

## Deliberate, disclosed staging

`auth/routes.py`'s `/me` endpoint has `has_profile` hardcoded to `False` in this phase — the
`profile` module doesn't exist until Phase 5. This is a deliberate staging decision, not an
oversight: auth needed to be usable and testable on its own before the profile module existed to
wire into it. `tests/conftest.py` and `api/v1/router.py` are likewise scoped to only what exists
so far, extended incrementally in each subsequent phase.

## Validation

`ruff check` clean, `compileall` clean, and the full test suite (`test_auth_flow.py`,
`test_email_password.py`, `test_health.py`, plus the `unit/` tests for security/email/settings)
passing against a real, isolated Postgres + Redis — 28/28 tests.
