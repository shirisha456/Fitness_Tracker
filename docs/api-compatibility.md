# API compatibility matrix: FastAPI → Spring Boot

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


**46 operations across 12 route groups.** The Python implementation is the
specification. This document is what the Java backend must reproduce.

Endpoint list and parameters were extracted from the running application's generated
OpenAPI schema; request/response shapes and status codes were captured by calling a
live instance, not inferred from source.

---

## Global conventions

Everything below depends on these. Getting them wrong breaks every endpoint at once.

### Response envelope

Success is always wrapped:

```json
{ "data": <payload>, "message": "optional human string" }
```

`message` appears only where the Python route sets it (register, logout, verify-email,
resend-verification, forgot-password, reset-password). `data` is `null` for endpoints
that return no payload. `204 No Content` responses have **no body at all**.

### Error envelope

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Request validation failed",
    "details": [{ "field": "email", "message": "...", "code": "INVALID_FORMAT" }],
    "correlation_id": "09749944-3e83-4c21-adf2-15c7fba194dd"
  }
}
```

`details` is always present, `[]` when empty. `code` is one of `VALIDATION_ERROR`,
`UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `RATE_LIMIT_EXCEEDED`,
`SERVICE_UNAVAILABLE`, `BAD_GATEWAY`, `BAD_REQUEST`, `TOKEN_EXPIRED`, `INTERNAL_ERROR`.

Java: a single `@RestControllerAdvice` producing this shape, plus an
`AuthenticationEntryPoint` and `AccessDeniedHandler` so Spring Security's own
rejections are rendered the same way rather than as the default Boot error body.

### Field naming — snake_case, everywhere

The Next.js frontend reads these names directly: `weight_kg` appears 31 times in the
frontend source, `performed_at` 18, `recorded_at` 15, and so on. **No field may be
renamed to camelCase.**

```java
spring.jackson.property-naming-strategy=SNAKE_CASE
```

with explicit `@JsonProperty` where a name does not derive cleanly (`DateRange.from` /
`DateRange.to`, which are serialisation aliases in Pydantic).

### Null handling

Absent optional values are serialised as `null`, **not omitted**. Do not set
`@JsonInclude(NON_NULL)` globally — the frontend destructures these fields.

### Dates and times

| Kind | Example | Java |
|---|---|---|
| timestamp | `"2026-09-18T16:48:51.077141Z"` | `Instant` + `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` disabled |
| date | `"2026-09-01"` | `LocalDate` |

One known deviation: Python always prints 6 fractional digits; `Instant.toString()`
trims trailing zeros, so a timestamp of `…51.100000Z` renders as `…51.1Z` in Java.
Both are valid ISO-8601 and the frontend parses both through `new Date(...)`. Contract
tests normalise timestamps rather than compare them literally. Flagged as R4 in the
migration plan.

### Numbers

`weight_kg` sent as `45` comes back as `45.0`. Java `Double` reproduces this; `BigDecimal`
is deliberately not used (rationale in [java-schema-compatibility.md](java-schema-compatibility.md)).

### Authentication

`Authorization: Bearer <access_token>`. A missing or malformed header returns **401**
with the standard envelope — *not* Spring Security's default 403. HS256, shared
`SECRET_KEY`. Access-token claims: `sub`, `role`, `email_verified`, `type: "access"`,
`jti`, `iat`, `exp`.

### Validation

FastAPI's default 422 is overridden — **all validation failures return 400**.

---

## Auth — `/api/v1/auth`

### POST `/auth/register` · no auth · **201**
```json
{ "email": "a@b.com", "password": "SecurePass123!", "password_confirm": "SecurePass123!" }
```
→ `{"data": {id, email, email_verified, role, created_at}, "message": "Registration successful. Please check your email to verify your account."}`

Rules: email lowercased and trimmed; password 8–128 chars matching
`(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,}`; `password == password_confirm`.
Side effects: creates an email-verification token, enqueues the email.
Errors: `400` validation, **`409` CONFLICT** "An account with this email already exists".

### POST `/auth/login` · no auth · **200**
```json
{ "email": "a@b.com", "password": "SecurePass123!" }
```
→ `{"data": {access_token, refresh_token, token_type: "bearer", expires_in: 900, user: {...}}}`

Records `user_agent` (truncated to 512) and `ip_address` on the refresh-token row.
Errors: `401` "Invalid email or password" (identical for unknown email and wrong
password — and a dummy Argon2 verify runs on the unknown-email path so timing does not
leak); `403` "Account is deactivated" when `is_active = false` or `deleted_at` set.

### POST `/auth/refresh` · no auth (token in body) · **200**
```json
{ "refresh_token": "..." }
```
→ same shape as login.

The highest-risk endpoint in the migration. Ordered behaviour:
1. Decode JWT, require `type == "refresh"` → `401` (`TOKEN_EXPIRED` if expired).
2. Load `refresh_tokens` by the JWT's `jti`; missing, or `user_id` mismatch → `401`.
3. **If `revoked_at` is already set → reuse detected.** Revoke *every* active token for
   that user, **commit**, log a warning, then `401` "Refresh token reuse detected.
   Please log in again."
4. Expired → `401`. `token_hash` mismatch → `401`.
5. User inactive/deleted → `403`.
6. Rotate: set `revoked_at` on the presented token, issue a fresh pair.

Step 3's commit-before-throw is deliberate and documented in the Python source: the
request transaction is rolled back by the error handler, so without an explicit commit
the revocation would be silently undone. See R1 in the migration plan.

### POST `/auth/logout` · no auth · **200**
`{"refresh_token": "..."}` → `{"data": null, "message": "Logged out successfully"}`.
**Idempotent** — an invalid, unknown or already-revoked token still returns 200.

### GET `/auth/verify-email?token=` · no auth · **200**
→ `{"data": {"email_verified": true}, "message": "Email verified successfully"}`.
Errors: `400` for invalid / already-used / expired token, or inactive user.

### POST `/auth/resend-verification` · **auth** · **200**
→ `{"data": null, "message": "If your email is unverified, a new verification link has been sent."}`.
Errors: `400` "Email is already verified". Invalidates prior unused tokens first.

### POST `/auth/forgot-password` · no auth · **200**
`{"email": "..."}` → `{"data": null, "message": "If an account exists with this email, a reset link has been sent."}`.
**Always 200** — no account enumeration. Silent no-op for unknown users and for
accounts with a `NULL` password_hash.

### POST `/auth/reset-password` · no auth · **200**
`{"token", "password", "password_confirm"}` → `{"data": null, "message": "Password reset successfully"}`.
Same password rules as register. **Revokes all refresh tokens** on success.
Errors: `400` invalid / used / expired token.

### GET `/auth/me` · **auth** · **200**
→ `{"data": {id, email, email_verified, role, has_profile, created_at}}`.
`has_profile` is a live existence check against `profiles`.

---

## Exercises — `/api/v1/exercises`

| Method | Path | Auth | Success |
|---|---|---|---|
| GET | `/exercises?category=` | yes | 200 |
| POST | `/exercises` | yes | **201** |

GET returns `{"data": [{id, name, category, muscle_group, equipment}]}` ordered by name,
optionally filtered by `category`. Unfiltered — this returns other users' custom
exercises too, which is a deliberate product decision documented in the Python source.

POST is **get-or-create, case-insensitive** on name. It returns `201` even when it
returned a pre-existing row. Body: `{name, category?}` (`category` defaults to `other`).
Preserve the 201-on-existing quirk. The Python version wraps the insert in a SAVEPOINT
so losing a duplicate-name race does not poison the request transaction — in Java, the
equivalent is catching `DataIntegrityViolationException` in a `REQUIRES_NEW` boundary
and re-reading.

---

## Workouts — `/api/v1/workouts`

| Method | Path | Auth | Success |
|---|---|---|---|
| POST | `/workouts` | yes | 201 |
| GET | `/workouts?date_from=&date_to=` | yes | 200 |
| GET | `/workouts/{id}` | yes | 200 |
| PUT | `/workouts/{id}` | yes | 200 |
| DELETE | `/workouts/{id}` | yes | **204** |

Create/update body:
```json
{ "name": "Leg day", "performed_at": "2026-09-01", "notes": "ok",
  "exercises": [{ "exercise_id": "uuid", "sets": 3, "reps": 8, "weight_kg": 45, "notes": null }] }
```
Validation: `name` 1–255; `sets` 1–50; `reps` 1–1000; `weight_kg ≥ 0` or null;
`notes` ≤ 500 (exercise) / ≤ 2000 (workout).

Detail response nests the full exercise: `{id, name, performed_at, notes, created_at,
updated_at, exercises: [{id, exercise: {id, name, category, muscle_group, equipment},
order_index, sets, reps, weight_kg, notes}]}`.

**List returns a different shape** — summaries only:
`{id, name, performed_at, exercise_count}`, sorted `performed_at DESC, created_at DESC`.

`PUT` is a **full replace**: the child collection is cleared and rebuilt, with
`order_index` reassigned from array position. An unknown `exercise_id` →
`400 BAD_REQUEST` "Unknown exercise id(s): …". Another user's workout → **404**, never 403.

---

## Nutrition — `/api/v1/meals`, `/water-entries`, `/nutrition/summary`

| Method | Path | Auth | Success |
|---|---|---|---|
| POST / GET | `/meals` | yes | 201 / 200 |
| GET / PUT / DELETE | `/meals/{id}` | yes | 200 / 200 / **204** |
| POST | `/water-entries` | yes | 201 |
| GET | `/water-entries?date=` | yes | 200 |
| DELETE | `/water-entries/{id}` | yes | **204** |
| GET | `/nutrition/summary?date=` | yes | 200 |

Meal body: `{name, logged_at, calories, protein_g?, carbs_g?, fat_g?, notes?}`.
Validation: `calories` 0–20000; macros `≥ 0` or null; `amount_ml` 1–5000.
Meals sort `logged_at DESC, created_at DESC`; water entries sort `created_at DESC`.

Summary → `{date, total_calories, total_protein_g, total_carbs_g, total_fat_g,
total_water_ml}`. Computed as two SQL aggregates with `COALESCE`, so an empty day
returns zeros (`0` for ints, `0.0` for floats) rather than nulls.

---

## Progress — `/api/v1/measurements`, `/goals`

| Method | Path | Auth | Success |
|---|---|---|---|
| POST / GET | `/measurements` | yes | **201** / 200 |
| GET / PUT / DELETE | `/measurements/{id}` | yes | 200 / 200 / **204** |
| POST / GET | `/goals` | yes | 201 / 200 |
| PUT / DELETE | `/goals/{id}` | yes | 200 / **204** |

Measurement body: `{recorded_at, weight_kg?, body_fat_pct?, waist_cm?, chest_cm?,
hips_cm?, arm_cm?, notes?}`. Ranges: weight `>0..500`, body fat `0..100`,
waist/chest/hips `>0..300`, arm `>0..100`.

**POST `/measurements` is an upsert.** One row per user per day (unique constraint);
posting again for the same `recorded_at` updates in place and still returns `201`.

Goal body: `{title, target_weight_kg?, target_date?}`; `PUT` additionally takes
`status` (`active` | `achieved` | `abandoned`, default `active`).
Measurements sort `recorded_at DESC`; both support `date_from` / `date_to`.

---

## Profile — `/api/v1/profile`

| Method | Path | Auth | Success |
|---|---|---|---|
| GET | `/profile` | yes | 200, **404** when none |
| PUT | `/profile` | yes | 200 |

Body: `{display_name?, date_of_birth?, sex?, height_cm?, fitness_goal?, activity_level?}`.
`PUT` upserts and is a **true replace** — omitted fields are written as `NULL`.
`GET` before any `PUT` returns `404`; the frontend relies on this and on
`/auth/me`'s `has_profile`.

---

## Training insights — `/api/v1/training`

| Method | Path | Auth | Success |
|---|---|---|---|
| GET | `/training/overview` | yes | 200 |
| GET | `/training/recommendations` | yes | 200 |
| GET | `/training/exercises/{id}/history?limit=` | yes | 200 |
| GET | `/training/exercises/{id}/insights?explain=` | yes | 200 |

`limit` 1–50 (default 50). `explain` defaults `false`. Unknown exercise → `404`.
An exercise the caller has never logged → `200` with `insufficient_data`, never 404.

Closed vocabularies that must be reproduced **exactly**:

- `classification`: `progressing`, `stable`, `possible_plateau`, `declining`,
  `insufficient_data`, `not_applicable`
- `metric_basis`: `load`, `reps`, `none`
- `suggestion.kind`: `maintain`, `small_progression`, `review_exercise`,
  `insufficient_history`, `consult_professional`
- `explanation_source`: `deterministic`, `ai`

> The migration brief lists four classifications. The implemented engine has **six** —
> `declining` and `not_applicable` were added deliberately, with reasoning in
> [adaptive-training.md](adaptive-training.md) §3. All six must be ported.

`date_range` serialises with the keys **`from`** and **`to`** (Pydantic aliases), not
`from_date` / `to_date`.

`explain=true` may call OpenAI. On **any** AI failure the endpoint still returns `200`
with `explanation_source: "deterministic"`. AI availability must never change the
status code, the classification, or any metric.

---

## AI coach — `/api/v1/ai`

| Method | Path | Auth | Success |
|---|---|---|---|
| POST | `/ai/generate-workout` | yes | 200 |
| POST | `/ai/generate-meals` | yes | 200 |
| POST | `/ai/chat` | yes | 200 |
| GET | `/ai/recommendations` | yes | 200 |

Unlike training insights, these **fail** when the provider is unavailable — that is the
existing contract and the frontend handles it:

| Condition | Status | Code |
|---|---|---|
| No API key configured | 503 | `SERVICE_UNAVAILABLE` |
| Provider auth error | 503 | `SERVICE_UNAVAILABLE` |
| Rate limit / quota | 503 | `SERVICE_UNAVAILABLE` |
| Malformed or unparseable model output | 502 | `BAD_GATEWAY` |
| Any other provider error | 502 | `BAD_GATEWAY` |

`generate-workout` body `{goal, equipment?, duration_minutes=45 (10–180), difficulty=intermediate}`
→ `{name, exercises: [{exercise_name, exercise_id | null, sets, reps, notes}]}`.
The model returns names only; the service resolves them to UUIDs case-insensitively and
emits `null` for anything unrecognised. Prompt-visible exercises are scoped to
`created_by_user_id IS NULL OR = me` — a prompt-injection boundary that must be ported.

`generate-meals` body `{meal_type, dietary_restrictions?, target_calories?}` →
`{suggestions: [{name, estimated_calories, protein_g, carbs_g, fat_g}]}`.

`chat` body `{messages: [{role: "user"|"assistant", content}]}` (1–20 messages, content
1–4000) → `{message: "..."}`. Empty array → `400`.

`recommendations` → `{recommendations: ["...", ...]}`. Its prompt now includes
deterministic training signals computed by the insights module.

---

## Health — `/api/v1`

| Method | Path | Auth | Success |
|---|---|---|---|
| GET | `/health` | no | 200 `{"data": {"status": "ok"}}` |
| GET | `/ready` | no | 200 or **503** |

`/ready` → `{"data": {"status": "ok"|"degraded", "checks": {"database": "up"|"down",
"redis": "up"|"down"}}}`, with HTTP **503** when either dependency is down.

These paths are part of the contract — the Docker healthcheck and nginx both use them.
Spring Boot Actuator's `/actuator/health` has a different shape and path, so
`/api/v1/health` and `/api/v1/ready` must be kept as explicit controllers. Actuator is
mounted separately for metrics and is **not** a replacement for these.

---

## Things that must not change

1. All field names stay snake_case.
2. `data` / `error` envelopes on every response.
3. Validation failures are `400`, never `422`.
4. Not-found on someone else's row is `404`, never `403`.
5. `DELETE` returns `204` with no body.
6. `POST /exercises` returns `201` even when the row already existed.
7. `POST /measurements` upserts and returns `201`.
8. `/auth/logout` is idempotent and always `200`.
9. `/auth/forgot-password` always `200`.
10. Training insights return `200` when AI fails; `/ai/*` return `502`/`503`.
11. Enum values stay lowercase.
12. `date_range` uses the keys `from` / `to`.

## Known acceptable deviations

| # | Deviation | Why acceptable |
|---|---|---|
| 1 | Validation `details[].message` text differs (Jakarta vs Pydantic wording) | The frontend renders `error.message` and uses `details[].field`; it never matches on message text. Structure, `field` and `code` are preserved. |
| 2 | Timestamp trailing-zero truncation (`.1Z` vs `.100000Z`) | Both valid ISO-8601; frontend parses via `new Date()`. |
| 3 | `correlation_id` values differ per request | Already non-deterministic; contract tests ignore it. |

Each is recorded here rather than discovered later during cutover.

## Accepted deviation: response headers

Bodies and status codes are identical across all compared endpoints. Headers are not, and
the difference is one-directional — Java sends everything Python sends, plus Spring
Security's defaults:

| Header | Python | Java |
|---|---|---|
| `Cache-Control` | absent | `no-cache, no-store, max-age=0, must-revalidate` |
| `Expires` | absent | `0` |
| `Pragma` | absent | `no-cache` |
| `Vary` | absent | `Origin`, `Access-Control-Request-Method`, `Access-Control-Request-Headers` |
| `X-Content-Type-Options` | absent | `nosniff` |
| `X-Frame-Options` | absent | `DENY` |
| `X-XSS-Protection` | absent | `0` |
| Body framing | `Content-Length` | `Transfer-Encoding: chunked` |
| Status line | `HTTP/1.1 200 OK` | `HTTP/1.1 200` |

**Accepted** because every difference is additive or cosmetic, the additions are security
headers an API should have been sending anyway, and no client depends on the absent ones.
The reason phrase is optional in HTTP/1.1 and is not parsed by browsers, `fetch`, or the
Next.js BFF. All 85 frontend flow checks pass against either backend.

`X-XSS-Protection: 0` is not a downgrade: the header's non-zero values enable a legacy
browser auditor that is itself exploitable, and explicitly disabling it is current
guidance.

## Accepted deviation: validation error prose

For a parameter that is present but unconvertible, both backends return the same status
(400), the same `error.code` (`VALIDATION_ERROR`), the same `details[].code`
(`INVALID_FORMAT`) and the same `details[].field`. Only the human-readable
`details[].message` differs:

| Request | Python | Java |
|---|---|---|
| `?date=not-a-date` | `Input should be a valid date or datetime, invalid character in year` | `Invalid value` |
| `/workouts/not-a-uuid` | `Input should be a valid UUID, invalid character: found \`n\` at 1` | `Invalid value` |

**Accepted.** Pydantic's strings are generated per type with positional detail; reproducing
them in Java would mean reimplementing that text for every convertible type, for a field
that is displayed rather than branched on. `compare_backends.py --suite malformed` compares
these responses with `details[].message` stripped, and separately asserts that Java's
message is non-empty and contains no class names, package names or stack frames — so the
deviation is bounded and checked, not merely tolerated.

## Fixed: missing required query parameters returned 500

Before this was corrected, a required query parameter that was simply absent reached the
generic exception handler and produced `500 INTERNAL_ERROR`:

| Request | Python | Java (before) | Java (now) |
|---|---|---|---|
| `GET /nutrition/summary` | `400` `query.date` / `Field required` | **`500`** | `400` `query.date` / `Field required` |
| `GET /auth/verify-email` | `400` `query.token` / `Field required` | **`500`** | `400` `query.token` / `Field required` |

Found by the Phase 13 load test, which recorded 8,720 HTTP 500s on `/nutrition/summary`.
Every request the response-parity suite compared was well-formed, so none of them ever hit
this path — the `malformed` suite now covers absent parameters, wrong types, unparseable
identifiers and unknown routes.
