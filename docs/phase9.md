# Phase 9 — Frontend Nutrition

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Nutrition dashboard and meal CRUD.

## What was built

- **`MealForm`** — name, date, calories, protein/carbs/fat, notes; shared between create/edit.
- **`WaterQuickAdd`** — two one-tap buttons (+250ml / +500ml) that `POST` directly and
  `router.refresh()`, no form.
- **Nutrition page** (`/nutrition`) — four summary cards (calories, protein, carbs, water) fed
  by the backend's aggregation endpoint, the water quick-add widget, and today's meal list.
- Meal detail/edit pages, BFF routes for `meals`, `water-entries`, and `nutrition/summary`.

## Validation

`npm run lint`/`npm run build` clean. Real end-to-end browser test: added water (confirmed in
the summary), logged a meal, confirmed the daily summary aggregated it correctly, edited it,
deleted it, confirmed the water entry was untouched by the meal deletion. No Select-widget
complications this phase — nutrition forms use plain inputs.
