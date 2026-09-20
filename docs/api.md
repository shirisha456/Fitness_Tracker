# API Reference

All backend routes are mounted under `/api/v1`. Every response body is either
`{"data": ..., "message"?: string}` on success or the structured error envelope below on
failure. Live interactive docs are available at `/api/docs` (Swagger UI) whenever
`ENVIRONMENT != production`.

## Error envelope

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [{"field": "email", "message": "value is not a valid email address", "code": "INVALID_FORMAT"}],
    "correlation_id": "b3f1c2..."
  }
}
```

`code` is one of `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`,
`RATE_LIMIT_EXCEEDED`, `SERVICE_UNAVAILABLE`, `BAD_GATEWAY`, `BAD_REQUEST`, `INTERNAL_ERROR`.

## Health

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/health` | none | Liveness — process is running |
| GET | `/ready` | none | Readiness — checks DB + Redis, `503` if either is down |

## Auth

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/register` | none | Also queues a verification email |
| POST | `/auth/login` | none | Returns access + refresh token pair |
| POST | `/auth/refresh` | none (refresh token in body) | Rotates the token pair; reuse of a revoked token revokes the whole session set |
| POST | `/auth/logout` | none (refresh token in body) | Idempotent |
| GET | `/auth/verify-email?token=` | none | |
| POST | `/auth/resend-verification` | bearer | |
| POST | `/auth/forgot-password` | none | Always returns success — no email enumeration |
| POST | `/auth/reset-password` | none (reset token in body) | Revokes all existing sessions |
| GET | `/auth/me` | bearer | Includes `has_profile` |

## Workouts

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/exercises` | bearer | `?category=strength\|cardio\|mobility\|other` |
| POST | `/workouts` | bearer | |
| GET | `/workouts` | bearer | `?date_from=&date_to=`, returns summaries (not full detail) |
| GET | `/workouts/{id}` | bearer | Owner-only, `404` otherwise |
| PUT | `/workouts/{id}` | bearer | Full replace |
| DELETE | `/workouts/{id}` | bearer | |

## Training insights

Deterministic analytics over the caller's own workout history. Every response carries
`sessions_analyzed`, a `date_range` and an `evidence[]` list, so any verdict can be traced back
to the numbers it came from. Rules and thresholds: [adaptive-training.md](adaptive-training.md).

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/training/overview` | bearer | Consistency, 7-day volume vs the previous 7 days, and per-exercise verdicts (most recently trained first) |
| GET | `/training/recommendations` | bearer | Only actionable suggestions, each with its evidence |
| GET | `/training/exercises/{id}/history` | bearer | `?limit=` (1–50). Session table plus personal bests, which are computed over the full history before trimming |
| GET | `/training/exercises/{id}/insights` | bearer | `?explain=true` asks the AI coach to reword the explanation. Metrics and classification are identical either way; any AI failure falls back to deterministic text and still returns `200` |

`classification` is one of `progressing`, `stable`, `possible_plateau`, `declining`,
`insufficient_data`, `not_applicable`. `suggestion.kind` is one of `maintain`,
`small_progression`, `review_exercise`, `insufficient_history`, `consult_professional`.

An unknown exercise id returns `404`. An exercise the caller has never logged returns
`insufficient_data` with no metrics — never another user's history.

## Nutrition

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST / GET | `/meals` | bearer | `?date_from=&date_to=` on GET |
| GET / PUT / DELETE | `/meals/{id}` | bearer | |
| POST / GET | `/water-entries` | bearer | `?date=` on GET |
| DELETE | `/water-entries/{id}` | bearer | |
| GET | `/nutrition/summary?date=` | bearer | Aggregates calories/macros/water for one day |

## Progress

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/measurements` | bearer | Upserts — same date updates in place |
| GET | `/measurements` | bearer | `?date_from=&date_to=` |
| GET / PUT / DELETE | `/measurements/{id}` | bearer | |
| POST | `/goals` | bearer | |
| GET | `/goals` | bearer | |
| PUT / DELETE | `/goals/{id}` | bearer | |

## Profile

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/profile` | bearer | `404` if never created |
| PUT | `/profile` | bearer | Upsert |

## AI Coach

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/ai/generate-workout` | bearer | Constrained to real exercise-library names |
| POST | `/ai/generate-meals` | bearer | |
| POST | `/ai/chat` | bearer | |
| GET | `/ai/recommendations` | bearer | Context-aware from recent activity |

All four return `503 SERVICE_UNAVAILABLE` if `OPENAI_API_KEY` isn't configured.

## Authentication

Bearer JWT access token in `Authorization: Bearer <token>`, obtained from `/auth/login` or
`/auth/refresh`. Access tokens expire after 15 minutes; refresh tokens after 7 days. See
[ADR 001](adr/001-jwt-refresh-rotation.md) for the rotation/reuse-detection model.

The frontend never calls these routes directly from the browser — see
[ADR 002](adr/002-bff-pattern-httponly-cookies.md) for the BFF proxy layer at
`frontend/app/api/**`, which is what the browser actually talks to.
