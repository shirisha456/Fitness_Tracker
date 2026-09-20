# Adaptive training insights

## 1. The problem

A workout log answers "what did I do?". It does not answer the question people
actually care about: *is any of this working?*

Answering that means looking across sessions — is the load going up, has this lift
not moved in a month, am I training more or less than I was. Doing it by eye over a
list of workouts is tedious and easy to get wrong, and handing the raw history to an
LLM and asking "am I progressing?" produces an answer nobody can check. An LLM will
happily assert a 7% volume increase it never computed.

So the analysis is deterministic and the LLM only does prose.

## 2. Architecture

```
backend/app/modules/training_insights/
├── rules.py        every threshold + the classification rules, as documentation
├── analytics.py    pure functions: no DB, no clock, no network
├── repository.py   the only code here that touches the database
├── service.py      orchestration; the one place the AI hop lives
├── schemas.py      Pydantic responses (also the engine's return types)
└── routes.py       APIRouter(prefix="/training")
```

The module owns **no tables**. It reads `workouts` / `workout_exercises`, which is
why there is no migration for this feature (see §5).

The layering is the point:

| Layer | Responsibility | Depends on |
|---|---|---|
| `repository` | fetch rows, enforce ownership in SQL | database |
| `analytics` | metrics, classification, evidence, suggestions, fallback prose | nothing |
| `service` | wire the two together, optionally call the AI | both |
| `ai/service` | reword an already-computed result | OpenAI |

`analytics.py` takes a `LoggedSet` list and `today` as arguments and returns
schema objects. That is what makes the 53 unit tests possible without a database,
and it is what makes the verdicts reproducible.

### Why the AI hop cannot affect the numbers

`get_exercise_insight` builds the complete insight **before** it looks at the
`explain` flag. The AI call can only overwrite two fields — `explanation` and
`explanation_source`. A failure is caught as `AppException` and the deterministic
text stays. The endpoint returns 200 either way.

```
rows ──► analytics ──► ExerciseInsight ──┬──► response
                                          └──► (explain=true) ──► AI ──► explanation only
```

## 3. Analytics rules

Every constant lives in `rules.py`. Nothing in `analytics.py` hard-codes a number.

### Session model

One *session* = one workout containing the exercise. Rows are aggregated per
workout, so a drop set logged as two rows is one session:

| Field | Definition |
|---|---|
| `total_sets` | sum of `sets` across the workout's rows for that exercise |
| `total_reps` | sum of `sets x reps` |
| `reps_per_set` | set only when every row used the same rep count |
| `top_weight_kg` | max non-null, non-zero `weight_kg`; `None` if unweighted |
| `volume_kg` | sum of `sets x reps x weight` over **weighted rows only**; `None` if none |

Sessions are ordered by `(performed_at, workout.created_at, workout_id)` — stable
under unordered input and duplicate dates.

### Primary metric

The most recent `TREND_WINDOW_SESSIONS` (5) sessions form the analysis window.

- **Load basis** when the window has at least `MIN_SESSIONS_FOR_TREND` (3) weighted
  sessions. Primary metric = `top_weight_kg`.
- **Reps basis** otherwise. Primary metric = `total_reps`. This is the only honest
  signal for bodyweight strength work (pull-ups, push-ups).
- Weighted volume is never the primary metric on a reps basis, and
  `volume_change_percent` is `null` there.

### Classification

Evaluated in this order:

| # | Verdict | Condition |
|---|---|---|
| 1 | `not_applicable` | exercise category is not `strength` |
| 2 | `insufficient_data` | fewer than `MIN_SESSIONS_FOR_TREND` (3) comparable sessions |
| 3 | `possible_plateau` | the last `PLATEAU_MIN_SESSIONS` (4) sessions span at least `PLATEAU_MIN_SPAN_DAYS` (14) days, and both the primary metric and session volume stay within a ±`MIN_MEANINGFUL_CHANGE_PCT` (2%) band |
| 4 | `progressing` | primary metric up more than 2% across the window, and volume not down more than 2% |
| 5 | `declining` | primary metric down more than 2% |
| 6 | `stable` | everything else |

Three deliberate choices worth defending:

- **Plateau is checked before progression.** A jump five sessions ago followed by
  four flat sessions is a plateau *now*. Checking progression first would hide it.
- **The 14-day span requirement** is what stops four hard sessions in one week from
  being called a plateau. Session count alone is not enough.
- **Load up but volume meaningfully down is `stable`, not `progressing`.** Going
  heavier while doing much less total work is a trade-off, and the evidence string
  says so.

`not_applicable` and `declining` are additions to the four verdicts the feature
brief listed. `not_applicable` exists because `sets`/`reps`/`weight` cannot express
distance or duration — claiming a trend for a 5 km run from `1 x 1` would be
fabricated. `declining` exists because reporting a 15% load drop as "stable" would
be wrong.

### Volume and consistency

- **Volume trend**: weighted volume over `[today-6, today]` vs `[today-13, today-7]`.
  Rows without a weight contribute nothing. `change_percent` is `null` when the
  previous window is zero — never infinity, never 100%.
- **Consistency**: workouts in the last 7 days, in the previous 7 days, the number of
  active weeks over `CONSISTENCY_TREND_WEEKS` (8), and the average per week.

These are deliberately named as behavioural counts. They are **not** called recovery,
readiness or fitness — nothing in this data model supports those words.

### Personal bests

Heaviest load, best session volume, and most reps at the heaviest load. Ties are
dated from when the record was *first* set. All fields are `null` when the history
has no weighted sessions — the engine does not invent records.

### Next-session suggestions

| Verdict | Suggestion |
|---|---|
| `progressing` | `maintain` — the current progression is working |
| `stable` (load basis) | `small_progression` with a specific load |
| `stable` (reps basis) | `maintain` |
| `possible_plateau` / `declining` | `review_exercise` — **no** load increase |
| `insufficient_data` / `not_applicable` | `insufficient_history` |
| any pain/injury note | `consult_professional`, overriding everything above |

The numeric suggestion is `PROGRESSION_STEP_PCT` (2.5%) of the current load, clamped
to `[MIN_LOAD_INCREASE_KG, MAX_LOAD_INCREASE_KG]` = [0.5, 2.5] kg and rounded to
0.5 kg. So 45 kg → 46 kg, and 200 kg → 202.5 kg rather than 205 kg.

Nothing is ever applied automatically. The suggestion is a sentence on a page; the
user decides whether to act on it when they log their next workout.

### The medical boundary

This is not a medical application and does not diagnose anything.

Analytics read numbers only — free-text notes never enter the analytics layer. What
the repository does read notes for is a blunt keyword screen
(`rules.mentions_medical_concern`). A hit sets one boolean, and that boolean:

- replaces the training suggestion with a referral to a qualified professional;
- suppresses any numeric load suggestion.

Only the boolean crosses the module boundary. The note text never reaches the
analytics layer, the API response, a log line, an exception message or the AI prompt.

## 4. AI integration

The AI receives a compact summary of what was already computed — measured at ~440
characters in end-to-end validation — not the user's history:

```
Exercise: Bench Press
Classification: progressing
Sessions analyzed: 3
Metric basis: load
Date range: 2026-08-27 to 2026-09-10
Latest top load: 45.0 kg
Previous top load: 42.5 kg
Change in primary metric: 12.5%
Evidence:
- Top load moved from 40 kg to 45 kg across 3 sessions (+12.5%)
...
```

The system prompt tells it to restate, not to calculate, and not to introduce a
number that is not in the analysis.

It reuses the existing AI module entirely: `_client()`, `_complete_text()`,
`_translate_openai_error()`, the same settings. No second OpenAI integration.

The **existing AI coach** (`/ai/recommendations`) was not replaced. It now receives
deterministic training signals alongside its previous context:

```
Recent training signals (already computed — do not recalculate or contradict):
- Training frequency: 3 sessions in the last 7 days vs 2 the week before
- Squat: possible_plateau
- Bench Press: progressing
```

## 5. Database and query design

**No migration was added.** The existing schema already stores what the feature
needs:

- `workouts.performed_at` is a log date; there is no plan/actual split and no
  completion status. Rows are written after the fact, so `sets`, `reps` and
  `weight_kg` on `workout_exercises` *are* the performed values.
- Adding `completion_status` or `effort_rating` would mean adding fields the
  application has no way to populate honestly.

One real limitation follows from this and is not papered over: **the system cannot
tell whether a user completed a workout they intended to do**, because nothing
distinguishes a plan from a log. Recommendations are therefore restricted to trends
over what was actually logged. A workout dated after today is excluded from every
calculation, since it cannot be something that happened.

### Queries

Three, none of them per-exercise:

| Query | Purpose |
|---|---|
| `fetch_exercise_history` | one exercise's rows, 365-day window |
| `fetch_recent_history_by_exercise` | **every** exercise at once, 180-day window, grouped in application code |
| `fetch_workout_dates` | workout dates only, for consistency |

The overview is 2 queries regardless of how many exercises the user trains. Workout
dates are queried separately because an inner join to `workout_exercises` would
silently drop a workout logged with no exercises.

Every statement filters on `workouts.user_id`. Passing another user's `exercise_id`
returns an empty result rather than their data.

### Indexes — and why none were added

The driving query is:

```sql
FROM workout_exercises we JOIN workouts w ON we.workout_id = w.id
WHERE w.user_id = ? AND w.performed_at BETWEEN ? AND ?
```

`ix_workouts_user_performed (user_id, performed_at)` (migration 008) serves the
filter, and `ix_workout_exercises_workout_id` serves the join. Driving from
`workout_exercises.exercise_id` instead would be far less selective than the
per-user filter, so an index on that column would not be used by this query. It was
not added.

No latency or throughput numbers are claimed here, because none were measured.

## 6. Edge cases handled

| Case | Behaviour |
|---|---|
| Fewer than 3 sessions | `insufficient_data`, no suggestion |
| One repeated workout | `insufficient_data` — never a plateau |
| 4 flat sessions inside 2 weeks | `stable`, not a plateau (span rule) |
| Missing `weight_kg` | excluded from volume; falls back to a reps basis |
| `weight_kg = 0` (validation allows it) | treated as unweighted |
| Cardio / mobility | `not_applicable` |
| Changed rep or set counts | reflected in `total_reps` / volume; `reps_per_set` becomes `null` |
| Rows out of chronological order | sorted deterministically before analysis |
| Two workouts on the same date | two sessions, ordered by `created_at` |
| Same exercise twice in one workout | aggregated into one session |
| Custom exercises | treated like any other — category drives the rules |
| Deleted workouts | `ON DELETE CASCADE` removes the rows; history simply shortens |
| Future-dated workouts | excluded everywhere |
| Very old history | bounded by the 365/180-day lookback windows |
| Zero-baseline percentage | `null`, never infinity |
| AI down / unconfigured | deterministic explanation, HTTP 200 |

**Floating point.** `weight_kg` is `double precision` in Postgres, and the engine
keeps it that way rather than converting to `Decimal`. Every comparison that must be
exact goes through `WEIGHT_COMPARISON_EPSILON_KG` (1e-6), and every trend decision
uses a 2% threshold — orders of magnitude larger than float error on gym weights.
Outputs are rounded at the boundary. `Decimal` would add conversion noise at every
read for no behavioural gain; if money or medical dosing were involved the answer
would be different.

## 7. Limitations

- Cannot verify a *prescribed* workout was completed — there is no plan/actual split.
- No distance, duration, pace or heart rate, so cardio gets no trend at all.
- No RPE or effort rating, so "45 kg x 8 that felt easy" and "…that was a grinder"
  are indistinguishable.
- Rep-basis trends are weaker than load-basis ones: more reps at bodyweight is real
  progress, but body weight itself isn't factored in.
- Verdicts describe *logged numbers*, not fitness. Volume can rise while training
  quality falls.
- Thresholds are defensible defaults, not validated against outcome data. They are
  configurable constants precisely because they are judgement calls.
- Suggestions assume 0.5 kg loading granularity is achievable.
- The pain/injury screen is keyword-based: it will miss phrasings it doesn't know and
  will occasionally fire on an innocent note. It is deliberately tuned to fail toward
  *not* giving advice.

## 8. Testing strategy

| Layer | Where | What |
|---|---|---|
| Unit (53) | `tests/unit/test_training_analytics.py` | every classification, boundary values, aggregation edge cases, personal bests, suggestion bounds, consistency, volume, the medical screen — all pure, no database |
| Integration (22) | `tests/integration/test_training_insights.py` | real PostgreSQL, workouts created through the real HTTP API, strict user isolation, 404s, auth |
| AI | same file | mocked client only — never the real API. Covers success, provider failure, missing key, empty output, and that the prompt carries metrics rather than history |

Boundary tests are explicit about the thresholds: 100 → 102 kg (exactly 2.0%) is
`progressing`; 100 → 101.9 kg is `stable`.

## 9. Future improvements

- An `effort_rating` (RPE) column on `workout_exercises` would let the engine tell a
  comfortable session from a maximal one. It is a real gap — but it needs a UI to
  capture it, so it is a feature, not a field.
- Distance/duration columns would make cardio classifiable instead of `not_applicable`.
- Estimated 1RM (Epley/Brzycki) would let sessions at different rep ranges be
  compared, which the current load basis cannot do.
- A weekly digest is the one genuinely asynchronous use for these signals.
  Today's analytics are request-time by design: they are two indexed queries and a
  pass over a bounded row set, and moving them to a worker would add moving parts
  without fixing a measured problem.
- Per-user thresholds, once there is outcome data to justify moving them.
