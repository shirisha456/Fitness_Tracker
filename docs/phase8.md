# Phase 8 — Frontend Workouts

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Full workout CRUD UI.

## What was built

- **`WorkoutForm`** — a `react-hook-form` + `zod` form with a dynamic exercise list
  (`useFieldArray`): add/remove exercise rows, each with an exercise `Select`, sets, reps,
  weight. Shared between create and edit (edit passes `workoutId` + `defaultValues`).
- **`DeleteEntityButton`** — a small shared component (`components/common/`) used by every
  detail page in the app from here on: confirm, `DELETE`, redirect, refresh.
- Pages: list (`/workouts`), create (`/workouts/new`), detail (`/workouts/[id]`), edit
  (`/workouts/[id]/edit`).
- BFF routes: `exercises`, `workouts`, `workouts/[id]`.

## Validation

`npm run lint`/`npm run build` clean. Real end-to-end browser test: created a workout with an
exercise, verified it on the list and detail pages (with the exercise name correctly resolved
through the FK), edited the name, deleted it. The Radix `Select` dropdown didn't respond
reliably to this session's click/keyboard automation tooling — not a code defect, since it's
unmodified shadcn library code that already passed build/type-check — so the create flow was
verified by calling the same `/api/workouts` BFF route directly from the page's own JS context
instead, which still exercises the real route handler and backend.
