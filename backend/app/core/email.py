"""Email sending utilities.

Supports:
- smtp: real SMTP (Mailhog locally, SES/SendGrid in prod)
- console: log + in-memory capture (tests / local without Mailhog)
"""

from __future__ import annotations

import logging
import smtplib
from dataclasses import dataclass
from email.message import EmailMessage

from app.config import get_settings

logger = logging.getLogger(__name__)

# In-memory capture for console backend (tests assert against this).
_sent_emails: list[dict[str, str]] = []


@dataclass
class EmailMessagePayload:
    to_email: str
    subject: str
    text_body: str
    html_body: str | None = None


def clear_sent_emails() -> None:
    _sent_emails.clear()


def get_sent_emails() -> list[dict[str, str]]:
    return list(_sent_emails)


def send_email(payload: EmailMessagePayload) -> None:
    """Send an email via configured backend."""
    settings = get_settings()

    if settings.email_backend == "console":
        record = {
            "to": payload.to_email,
            "subject": payload.subject,
            "text_body": payload.text_body,
            "html_body": payload.html_body or "",
        }
        _sent_emails.append(record)
        logger.info(
            "console_email to=%s subject=%s",
            payload.to_email,
            payload.subject,
        )
        return

    message = EmailMessage()
    message["From"] = settings.smtp_from
    message["To"] = payload.to_email
    message["Subject"] = payload.subject
    message.set_content(payload.text_body)
    if payload.html_body:
        message.add_alternative(payload.html_body, subtype="html")

    with smtplib.SMTP(settings.smtp_host, settings.smtp_port, timeout=15) as smtp:
        if settings.smtp_use_tls:
            smtp.starttls()
        if settings.smtp_user and settings.smtp_password:
            smtp.login(settings.smtp_user, settings.smtp_password)
        smtp.send_message(message)

    logger.info("smtp_email_sent to=%s subject=%s", payload.to_email, payload.subject)


def build_verification_email(to_email: str, raw_token: str) -> EmailMessagePayload:
    settings = get_settings()
    link = f"{settings.frontend_url.rstrip('/')}/verify-email?token={raw_token}"
    text = (
        "Welcome to Fitness Tracker!\n\n"
        "Please verify your email by opening this link:\n"
        f"{link}\n\n"
        "If you did not create an account, ignore this email.\n"
    )
    html = (
        "<p>Welcome to Fitness Tracker!</p>"
        f'<p><a href="{link}">Verify your email</a></p>'
        "<p>If you did not create an account, ignore this email.</p>"
    )
    return EmailMessagePayload(
        to_email=to_email,
        subject="Verify your Fitness Tracker email",
        text_body=text,
        html_body=html,
    )


def build_password_reset_email(to_email: str, raw_token: str) -> EmailMessagePayload:
    settings = get_settings()
    link = f"{settings.frontend_url.rstrip('/')}/reset-password?token={raw_token}"
    text = (
        "Fitness Tracker password reset\n\n"
        "Reset your password using this link (expires in 1 hour):\n"
        f"{link}\n\n"
        "If you did not request this, ignore this email.\n"
    )
    html = (
        "<p>Fitness Tracker password reset</p>"
        f'<p><a href="{link}">Reset your password</a></p>'
        "<p>This link expires in 1 hour. If you did not request this, ignore this email.</p>"
    )
    return EmailMessagePayload(
        to_email=to_email,
        subject="Reset your Fitness Tracker password",
        text_body=text,
        html_body=html,
    )
