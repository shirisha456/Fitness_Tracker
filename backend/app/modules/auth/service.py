"""Auth business logic: register, login, refresh rotation, logout."""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.core.exceptions import AppException
from app.core.security import (
    TokenError,
    create_access_token,
    create_refresh_token,
    decode_token,
    generate_secure_token,
    hash_password,
    hash_token,
    verify_password,
)
from app.modules.auth.models import (
    EmailVerificationToken,
    PasswordResetToken,
    RefreshToken,
    User,
    UserRole,
)
from app.modules.auth.schemas import (
    LoginRequest,
    MeResponse,
    RegisterRequest,
    ResetPasswordRequest,
    TokenResponse,
    UserResponse,
)

logger = logging.getLogger(__name__)

# Precomputed Argon2 hash so failed logins still exercise verify_password (timing).
_DUMMY_PASSWORD_HASH = hash_password("timing-safe-dummy-password")


async def register_user(db: AsyncSession, payload: RegisterRequest) -> User:
    existing = await db.scalar(select(User).where(User.email == payload.email))
    if existing is not None:
        raise AppException(
            code="CONFLICT",
            message="An account with this email already exists",
            status_code=409,
        )

    user = User(
        email=payload.email,
        password_hash=hash_password(payload.password),
        role=UserRole.USER,
        email_verified=False,
        is_active=True,
    )
    db.add(user)
    try:
        await db.flush()
    except IntegrityError as exc:
        raise AppException(
            code="CONFLICT",
            message="An account with this email already exists",
            status_code=409,
        ) from exc
    await db.refresh(user)

    raw_token = await create_email_verification_token(db, user)
    _enqueue_verification_email(user.email, raw_token)
    return user


async def authenticate_user(
    db: AsyncSession,
    payload: LoginRequest,
    *,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> TokenResponse:
    user = await db.scalar(select(User).where(User.email == payload.email))

    password_hash = (
        user.password_hash
        if user is not None and user.password_hash is not None
        else _DUMMY_PASSWORD_HASH
    )
    password_ok = verify_password(payload.password, password_hash)

    if user is None or user.password_hash is None or not password_ok:
        raise AppException(
            code="UNAUTHORIZED",
            message="Invalid email or password",
            status_code=401,
        )

    if not user.is_active or user.deleted_at is not None:
        raise AppException(
            code="FORBIDDEN",
            message="Account is deactivated",
            status_code=403,
        )

    return await _issue_token_pair(
        db,
        user,
        user_agent=user_agent,
        ip_address=ip_address,
    )


async def refresh_tokens(
    db: AsyncSession,
    raw_refresh_token: str,
    *,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> TokenResponse:
    try:
        payload = decode_token(raw_refresh_token, expected_type="refresh")
    except TokenError as exc:
        raise AppException(
            code=exc.code,
            message=exc.message,
            status_code=401,
        ) from exc

    jti = payload.get("jti")
    user_id = payload.get("sub")
    if not jti or not user_id:
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401)

    try:
        token_uuid = uuid.UUID(jti)
        user_uuid = uuid.UUID(user_id)
    except ValueError as exc:
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401) from exc

    stored = await db.get(RefreshToken, token_uuid)
    if stored is None or stored.user_id != user_uuid:
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401)

    if stored.revoked_at is not None:
        # Possible token reuse — revoke all active tokens for this user.
        #
        # This MUST be committed before the 401 is raised. `get_db` rolls the request
        # transaction back on any exception, so a flush alone would be undone by the
        # very error that reports the reuse — detection would work while the actual
        # security action silently did nothing. Committing here deliberately ends the
        # request transaction early; nothing after this point writes.
        await _revoke_all_user_tokens(db, user_uuid)
        await db.commit()
        logger.warning("refresh_token_reuse_detected user_id=%s jti=%s", user_uuid, token_uuid)
        raise AppException(
            code="UNAUTHORIZED",
            message="Refresh token reuse detected. Please log in again.",
            status_code=401,
        )

    if stored.expires_at < datetime.now(UTC):
        raise AppException(code="UNAUTHORIZED", message="Refresh token expired", status_code=401)

    if stored.token_hash != hash_token(raw_refresh_token):
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401)

    user = await db.get(User, user_uuid)
    if user is None or not user.is_active or user.deleted_at is not None:
        raise AppException(code="FORBIDDEN", message="Account is deactivated", status_code=403)

    # Rotate: revoke old, issue new
    stored.revoked_at = datetime.now(UTC)
    await db.flush()

    return await _issue_token_pair(
        db,
        user,
        user_agent=user_agent,
        ip_address=ip_address,
    )


async def logout(db: AsyncSession, raw_refresh_token: str) -> None:
    try:
        payload = decode_token(raw_refresh_token, expected_type="refresh")
    except TokenError:
        # Idempotent logout — treat invalid tokens as already logged out
        return

    jti = payload.get("jti")
    if not jti:
        return

    try:
        token_uuid = uuid.UUID(jti)
    except ValueError:
        return

    stored = await db.get(RefreshToken, token_uuid)
    if stored is not None and stored.revoked_at is None:
        stored.revoked_at = datetime.now(UTC)
        await db.flush()


async def get_current_user_from_token(db: AsyncSession, access_token: str) -> User:
    try:
        payload = decode_token(access_token, expected_type="access")
    except TokenError as exc:
        raise AppException(
            code=exc.code,
            message=exc.message,
            status_code=401,
        ) from exc

    user_id = payload.get("sub")
    if not user_id:
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401)

    try:
        user_uuid = uuid.UUID(user_id)
    except ValueError as exc:
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401) from exc

    user = await db.get(User, user_uuid)
    if user is None or not user.is_active or user.deleted_at is not None:
        raise AppException(code="UNAUTHORIZED", message="Invalid token", status_code=401)

    return user


def to_me_response(user: User, *, has_profile: bool = False) -> MeResponse:
    return MeResponse(
        id=user.id,
        email=user.email,
        email_verified=user.email_verified,
        role=user.role.value if isinstance(user.role, UserRole) else str(user.role),
        has_profile=has_profile,
        created_at=user.created_at,
    )


def to_user_response(user: User) -> UserResponse:
    return UserResponse(
        id=user.id,
        email=user.email,
        email_verified=user.email_verified,
        role=user.role.value if isinstance(user.role, UserRole) else str(user.role),
        created_at=user.created_at,
    )


async def _issue_token_pair(
    db: AsyncSession,
    user: User,
    *,
    user_agent: str | None = None,
    ip_address: str | None = None,
) -> TokenResponse:
    role = user.role.value if isinstance(user.role, UserRole) else str(user.role)
    access_token, expires_in = create_access_token(
        user_id=user.id,
        role=role,
        email_verified=user.email_verified,
    )
    refresh_token, jti, expires_at = create_refresh_token(user_id=user.id)

    db.add(
        RefreshToken(
            id=uuid.UUID(jti),
            user_id=user.id,
            token_hash=hash_token(refresh_token),
            expires_at=expires_at,
            user_agent=user_agent[:512] if user_agent else None,
            ip_address=ip_address,
        )
    )
    await db.flush()

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        expires_in=expires_in,
        user=to_user_response(user),
    )


async def _revoke_all_user_tokens(db: AsyncSession, user_id: uuid.UUID) -> None:
    result = await db.scalars(
        select(RefreshToken).where(
            RefreshToken.user_id == user_id,
            RefreshToken.revoked_at.is_(None),
        )
    )
    now = datetime.now(UTC)
    for token in result.all():
        token.revoked_at = now
    await db.flush()


async def create_email_verification_token(db: AsyncSession, user: User) -> str:
    """Invalidate prior unused tokens and create a new verification token.

    Returns the raw token (only ever sent via email — never stored).
    """
    settings = get_settings()
    now = datetime.now(UTC)

    existing = await db.scalars(
        select(EmailVerificationToken).where(
            EmailVerificationToken.user_id == user.id,
            EmailVerificationToken.used_at.is_(None),
        )
    )
    for token in existing.all():
        token.used_at = now

    raw_token = generate_secure_token()
    db.add(
        EmailVerificationToken(
            user_id=user.id,
            token_hash=hash_token(raw_token),
            expires_at=now + timedelta(hours=settings.email_verification_expire_hours),
        )
    )
    await db.flush()
    return raw_token


async def verify_email(db: AsyncSession, raw_token: str) -> User:
    token_row = await db.scalar(
        select(EmailVerificationToken).where(
            EmailVerificationToken.token_hash == hash_token(raw_token)
        )
    )
    if token_row is None:
        raise AppException(
            code="BAD_REQUEST",
            message="Invalid or expired verification token",
            status_code=400,
        )
    if token_row.used_at is not None:
        raise AppException(
            code="BAD_REQUEST",
            message="Verification token has already been used",
            status_code=400,
        )
    if token_row.expires_at < datetime.now(UTC):
        raise AppException(
            code="BAD_REQUEST",
            message="Verification token has expired",
            status_code=400,
        )

    user = await db.get(User, token_row.user_id)
    if user is None or not user.is_active or user.deleted_at is not None:
        raise AppException(
            code="BAD_REQUEST",
            message="Invalid or expired verification token",
            status_code=400,
        )

    user.email_verified = True
    token_row.used_at = datetime.now(UTC)
    await db.flush()
    await db.refresh(user)
    return user


async def resend_verification_email(db: AsyncSession, user: User) -> None:
    if user.email_verified:
        raise AppException(
            code="BAD_REQUEST",
            message="Email is already verified",
            status_code=400,
        )
    raw_token = await create_email_verification_token(db, user)
    _enqueue_verification_email(user.email, raw_token)


async def request_password_reset(db: AsyncSession, email: str) -> None:
    """Always succeeds from the caller's perspective (no email enumeration)."""
    user = await db.scalar(select(User).where(User.email == email))
    if user is None or not user.is_active or user.deleted_at is not None:
        return
    if user.password_hash is None:
        # OAuth-only account — silently no-op
        return

    settings = get_settings()
    now = datetime.now(UTC)
    existing = await db.scalars(
        select(PasswordResetToken).where(
            PasswordResetToken.user_id == user.id,
            PasswordResetToken.used_at.is_(None),
        )
    )
    for token in existing.all():
        token.used_at = now

    raw_token = generate_secure_token()
    db.add(
        PasswordResetToken(
            user_id=user.id,
            token_hash=hash_token(raw_token),
            expires_at=now + timedelta(hours=settings.password_reset_expire_hours),
        )
    )
    await db.flush()
    _enqueue_password_reset_email(user.email, raw_token)


async def reset_password(db: AsyncSession, payload: ResetPasswordRequest) -> None:
    token_row = await db.scalar(
        select(PasswordResetToken).where(
            PasswordResetToken.token_hash == hash_token(payload.token)
        )
    )
    if token_row is None:
        raise AppException(
            code="BAD_REQUEST",
            message="Invalid or expired reset token",
            status_code=400,
        )
    if token_row.used_at is not None:
        raise AppException(
            code="BAD_REQUEST",
            message="Reset token has already been used",
            status_code=400,
        )
    if token_row.expires_at < datetime.now(UTC):
        raise AppException(
            code="BAD_REQUEST",
            message="Reset token has expired",
            status_code=400,
        )

    user = await db.get(User, token_row.user_id)
    if user is None or not user.is_active or user.deleted_at is not None:
        raise AppException(
            code="BAD_REQUEST",
            message="Invalid or expired reset token",
            status_code=400,
        )

    user.password_hash = hash_password(payload.password)
    token_row.used_at = datetime.now(UTC)
    # Force re-login everywhere after password change
    await _revoke_all_user_tokens(db, user.id)
    await db.flush()


def _enqueue_verification_email(to_email: str, raw_token: str) -> None:
    from app.modules.auth.tasks import send_verification_email

    _enqueue(send_verification_email, to_email, raw_token, kind="verification")


def _enqueue_password_reset_email(to_email: str, raw_token: str) -> None:
    from app.modules.auth.tasks import send_password_reset_email

    _enqueue(send_password_reset_email, to_email, raw_token, kind="password_reset")


def _enqueue(task, to_email: str, raw_token: str, *, kind: str) -> None:
    """Queue a transactional email without letting the broker fail the request.

    `.delay()` is itself a network call to Redis. Leaving it unguarded meant a broker
    outage propagated out of the route and `get_db` rolled the transaction back — so
    Redis being down took registration and password reset down with it. The email is
    the non-essential half of both flows: registration still succeeds and the user can
    recover via POST /auth/resend-verification or by requesting another reset link.
    """
    try:
        task.delay(to_email, raw_token)
    except Exception:
        logger.exception("email_enqueue_failed kind=%s to=%s", kind, to_email)
