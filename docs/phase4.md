# Phase 4 — Progress

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Body measurement check-ins and fitness goals.

## What was built

- **`BodyMeasurement`** — weight, body fat %, waist/chest/hips/arm circumference, notes.
  One row per user per day (`UniqueConstraint(user_id, recorded_at)`), with **upsert**
  semantics: logging a second check-in for a date that already has one updates it in place
  rather than creating a duplicate.
- **`Goal`** — title, optional target weight/date, and a status (`active` / `achieved` /
  `abandoned`).
- **Migration 005** adds the `goal_status` enum plus both tables.

## Validation

50/50 tests passing (44 from before plus 6 new progress tests), including the upsert-on-same-day
behavior and goal status transitions.
