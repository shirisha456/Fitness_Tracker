# Phase 12 — Frontend AI Coach

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


The AI hub page plus workout generation, meal ideas, and chat.

## What was built

- **AI hub** (`/ai`) — a recommendations card and three link cards to the sub-features.
- **Chat** (`/ai/chat`) — a minimal message-list UI, no persistence (messages live in React
  state only; refreshing the page clears the conversation).
- **Meal ideas** (`/ai/meals`) — a form (meal type, restrictions, target calories) that
  generates suggestions, each with a one-click "Log this meal" that posts straight to the real
  `meals` BFF route.
- **Workout generator** (`/ai/workout`) — goal, equipment, duration, difficulty (`Select`);
  generates a workout and offers "Save as workout", which filters out any exercise the AI
  invented that isn't in the real library before saving.

## Validation

`npm run lint`/`npm run build` clean. Real end-to-end browser test with no `OPENAI_API_KEY`
configured (deliberately, per secret-safety rules): all three generation surfaces correctly
surfaced the backend's graceful `"AI features are not configured."` 503 rather than crashing —
exactly matching the backend's own no-key test coverage from Phase 6 — and the hub page's
recommendations section correctly fell back to its empty state. No console errors on any page.
