# Phase 3 — Nutrition

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Meal logging, water intake, and a daily nutrition summary.

## What was built

- **`Meal`** — name, date, calories, and optional protein/carbs/fat/notes.
- **`WaterEntry`** — date + amount in millilitres.
- Full CRUD for meals (create, list with date filtering, get, update, delete), create/list/
  delete for water entries, and a `/nutrition/summary?date=` endpoint that aggregates the day's
  total calories, macros, and water in a single query using `func.coalesce(func.sum(...), 0)`
  so an empty day returns zeroes instead of nulls or a 404.
- **Migration 004** creates `meals` and `water_entries` — no enum types, no seed data.

## Validation

44/44 tests passing (37 from before plus 7 new nutrition tests), including the daily-summary
aggregation across multiple meals and a zero-entries day.
