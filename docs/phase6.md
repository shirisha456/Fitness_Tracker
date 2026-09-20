# Phase 6 — AI Coach

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


OpenAI-backed workout generation, meal suggestions, freeform chat, and activity-based
recommendations. The last backend module — with this phase done, the API surface is complete.

## What was built

- **Workout generation** — the model is given the *exact* list of exercises in the library and
  instructed to only use names from that list; the response is JSON-mode constrained. Server
  code then resolves each returned exercise name back to a real `Exercise.id` (case-insensitive),
  so a workout can be saved as a real `Workout` afterward. Names the model invents that aren't in
  the library come back with `exercise_id: null` and are filtered out before saving — never
  silently invented as fake exercises.
- **Meal suggestions** — three realistic meal ideas fitting a meal type, optional dietary
  restrictions, and optional calorie target.
- **Chat** — a system-prompted fitness coach persona, explicitly instructed to defer to a real
  doctor for anything medical.
- **Recommendations** — pulls the user's last 7 days of workouts, today's nutrition summary, and
  recent weight measurements as context, then asks for 2–3 short, specific tips.
- Graceful **503 Service Unavailable** with a clear message whenever `OPENAI_API_KEY` isn't
  configured, rather than a raw exception — verified directly in
  `test_generate_workout_503_when_no_api_key`.

## Validation

63/63 tests passing (56 from before plus 7 new AI tests). Every AI test mocks the OpenAI client
directly (same pattern as `test_email.py` mocking SMTP) — real API calls cost money and can't run
reliably in CI, so nothing here has ever depended on a live OpenAI key.
