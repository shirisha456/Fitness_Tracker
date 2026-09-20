# Case Study — Building Fitness Tracker Phase by Phase

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


## What this is

Fitness Tracker is a full-stack fitness platform: workout logging, nutrition and water tracking, body
progress and goals, a single-row user profile, and an AI coach that generates workouts and meal
plans and answers fitness questions. It was built phase-by-phase, one module at a time, aiming
for clean, reviewable history — one phase per commit, each independently validated before moving
on to the next.

## Why phase-by-phase

A single "big bang" commit makes a codebase hard to review and hides the reasoning behind any
individual decision. Instead, the phase plan followed the codebase's own natural dependency
structure: the six Alembic migrations (`001` auth → `002` email/password tokens → `003` workouts
→ `004` nutrition → `005` progress → `006` profile) gave a clear backend module order, and the
frontend phases followed the same order once the corresponding backend API existed to build
against.

Every phase followed the same discipline: build the exact files needed for that phase, validate
with the strongest check reasonably available (real Postgres/Redis via an isolated Docker
network namespace, real `pytest`/`npm run build`, and for every frontend phase, an actual browser
session against a live backend), then stop for review before continuing.

## Decisions made along the way

1. **`auth/routes.py`'s `/me.has_profile`** was hardcoded `False` in Phase 1 (before the
   `profile` module existed) and wired to the real `profile` module in Phase 5 once it shipped —
   a deliberate intermediate state, not an oversight.
2. **A speculative addition was caught and reverted during Phase 2's planning**, not shipped: an
   initial belief that the `exercises` table had no seed mechanism turned out to be wrong —
   migration `003_workouts.py` already seeds it via `op.bulk_insert`. No new script was added.

A handful of real rough edges were kept rather than silently cleaned up:

- **The dashboard's "Soon" buttons** (Phase 13) are disabled for features that are actually
  fully built and linked elsewhere in the app, apparently leftover placeholder UI never wired
  up after the real features shipped.
- **No `.dockerignore` anywhere in the repository** (Phase 14), which makes the frontend Docker
  build transfer `node_modules` as build context — slow, but correct.
- **`run_all_tests.ps1`/`run_verify.ps1`** (Phase 16) hardcode an absolute path
  (`C:\Users\Shirisha\Fitness_tracker\...`) rather than resolving the repo root dynamically —
  fragile if the repo is ever moved or renamed again.

## Infrastructure discipline

Every phase that needed real Postgres/Redis used an isolated Docker network namespace with no
host port bindings at all, specifically to avoid colliding with other unrelated projects already
running on the same machine (which, on the very first phase, briefly connected a test run to a
different project's real database by accident, caught and safely rolled back by Postgres's own
transactional DDL — see [Phase 1](phase1.md) for the incident and the isolation pattern adopted
afterward for every subsequent phase).

## Result

A 44-route Next.js frontend, a 6-module async FastAPI backend, a full Docker Compose stack, CI,
and production deploy config — built across 16 phases plus documentation, validated end to end
in real containers, not just unit tests, at every infrastructure milestone.
