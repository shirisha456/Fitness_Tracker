# Phase 2 — Workouts

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


The exercise library and user workout tracking.

## What was built

- **`Exercise`** — a curated, seeded library entry (name, category, muscle group, equipment).
  Not user-owned; every user reads from the same shared library.
- **`Workout`** / **`WorkoutExercise`** — a user's logged workout with an ordered list of
  exercises, each carrying its own sets/reps/weight/notes.
- Full CRUD for workouts (create, list with date filtering, get, update, delete) plus a
  read-only exercise-library endpoint with category filtering.
- **Migration 003** creates all three tables *and* seeds 10 starter exercises (Squat, Bench
  Press, Deadlift, Overhead Press, Barbell Row, Pull-up, Push-up, Running, Cycling, Plank) via
  `op.bulk_insert` directly in the migration.

## Ownership enforcement

Every workout read/update/delete checks `workout.user_id == user.id` and returns a generic
`404 Not Found` — not a `403 Forbidden` — when it doesn't match. This avoids leaking whether a
given ID exists at all to a user who doesn't own it.

## Correction made during this phase

A new seed script was initially planned for this phase, on the assumption that the `exercises`
table had no way to populate itself. That was wrong — migration 003 already seeds the library
via `op.bulk_insert`. No new script was needed.

## Validation

37/37 tests passing (the 28 from Phase 1 plus 9 new workout tests), including exercise
filtering, unknown-exercise-id rejection, and cross-user ownership checks.
