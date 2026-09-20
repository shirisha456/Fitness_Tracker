# Phase 13 — Frontend Dashboard

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


The dashboard — the last frontend phase. With this done, `/dashboard` finally resolves instead
of 404ing (every earlier phase's login flow redirected there).

## What was built

- **`WelcomeHeader`** — time-of-day greeting ("Good morning/afternoon/evening") plus today's date.
- **`EmptyStateCard`** / **`QuickActions`** — reusable empty-state and quick-action components.
- **Dashboard page** — three cards (today's workout, today's calories, latest weight), each
  either linking to real data when it exists or showing an empty-state prompt when it doesn't;
  the greeting name falls back to email when no profile display name is set.

## A known, unfixed rough edge

`QuickActions` and every `EmptyStateCard` CTA render **permanently disabled** buttons labeled
"Soon" — for logging a workout, logging a meal, and updating the profile. All three features are
fully built and linked elsewhere in the app (`/workouts/new`, `/nutrition/meals/new`, `/profile`).
This is leftover placeholder UI from before those features shipped, never wired up to the real
routes afterward — a user landing on an empty dashboard sees "Soon" buttons for features that
already work. Left as a known issue rather than fixed in this phase, to keep the phase scoped to
what it set out to build.

## Validation

`npm run lint`/`npm run build` clean, all 44 routes. Real end-to-end browser test: verified the
empty-state dashboard, then logged a workout + meal + measurement via the real BFF routes and
confirmed all three cards switched to their populated variants with correct cross-module data,
each linking to the right destination.
