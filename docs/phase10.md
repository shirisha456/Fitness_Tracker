# Phase 10 — Frontend Progress

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Measurement check-ins with a weight-trend chart, and goals.

## What was built

- **`MeasurementForm`** — date plus six optional body-measurement fields.
- **`WeightChart`** — a `recharts` line chart (via the shadcn `chart` wrapper) plotting weight
  over the last 90 days of measurements.
- **`GoalForm`** / **`GoalActions`** — create a goal; mark it achieved or delete it inline
  (no dedicated edit page — status changes are a one-click action, not a form).
- Progress page (`/progress`) combining the chart, latest-measurement summary, and goal list.

## No edit pages, by design

Neither measurements nor goals have a dedicated edit route. Measurements are upsert-by-day
(resubmitting the "new" form for a date that already has an entry updates it in place — this is
backend behavior from Phase 4, not something added here), and goal status changes go through
`GoalActions`'s inline buttons rather than a form. There simply are no
`progress/measurements/[id]/edit` or `progress/goals/[id]/edit` routes — the interaction model
doesn't need them.

## Validation

`npm run lint`/`npm run build` clean. Real end-to-end browser test: logged a measurement and
confirmed the recharts line rendered with correct axis ticks, added a goal, marked it achieved
(badge appeared, the "Mark achieved" button correctly disappeared since it's active-only),
deleted it, confirmed the chart data was untouched.
