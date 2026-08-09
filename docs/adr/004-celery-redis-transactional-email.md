# ADR 004 — Celery + Redis for transactional email, not inline SMTP

## Status
Accepted.

## Context
Registration and password-reset both need to send an email. Sending it inline, inside the
request/response cycle, means the client waits on a real SMTP round-trip (and the request fails
if the mail server is briefly unavailable) before getting a response that has nothing to do with
whether the email actually sent.

## Decision
`auth/service.py` never calls `send_email` directly. It calls `.delay()` on a Celery task
(`app.modules.auth.tasks.send_verification_email` / `send_password_reset_email`), routed to a
dedicated `email` queue, with Redis as both broker and result backend. A separate `worker`
container (and `beat` for anything scheduled) processes the queue independently of the API
process. `CELERY_TASK_ALWAYS_EAGER=true` makes tasks execute synchronously in-process for tests
and local dev without a running worker — the same code path either way, just synchronous vs.
queued.

## Consequences
- Registration/reset responses return as soon as the DB write succeeds, regardless of SMTP
  latency or a transient mail-server outage.
- A production deployment needs the `worker` process actually running, or emails silently queue
  forever without being sent — there's no synchronous fallback in production.
- Local/CI testing (`CELERY_TASK_ALWAYS_EAGER=true`) never exercises the real worker+broker
  path — Phase 14's Docker Compose validation was the first point in this project where the
  actual queued-and-delivered-by-a-separate-process flow was verified end-to-end.
