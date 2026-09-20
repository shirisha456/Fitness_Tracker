# Phase 5 — Profile

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Single-row-per-user profile, and the real wiring for `/auth/me.has_profile`.

## What was built

- **`Profile`** — display name, date of birth, sex, height, fitness goal, activity level.
  One row per user (`unique=True` on `user_id`), upserted via a single `PUT /profile`.
- **Migration 006** adds the `profile_sex` and `profile_activity_level` enums plus the
  `profiles` table.
- `auth/routes.py`'s `/me` endpoint is updated to its final form: it now calls
  `profile_service.has_profile(db, user)` instead of the Phase 1 hardcoded `False`.

## Why this was staged across two phases

Auth is the foundation every other module depends on, but profile didn't exist until now. Wiring
`has_profile` before the profile module existed would have been a forward reference with nothing
to reference. Building auth "complete but stubbed" in Phase 1 and finishing the wire-up here kept
every phase's code actually buildable and testable on its own, rather than requiring auth and
profile to land as one oversized change.

## Validation

56/56 tests passing (50 from before plus 6 new profile tests) — including
`test_me_has_profile_false_before_creation` and `test_me_has_profile_true_after_creation`,
which specifically exercise the auth↔profile wiring end-to-end.
