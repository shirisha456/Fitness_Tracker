"""Every threshold, vocabulary and rule constant used by the analytics engine.

Nothing in `analytics.py` hard-codes a number. A reviewer who wants to know
"why did the system say this?" should be able to answer it from this file plus
the evidence strings the engine emits.

## Classification rules

Sessions for an exercise are ordered oldest -> newest and the most recent
`TREND_WINDOW_SESSIONS` form the *analysis window*. Within that window:

1. `not_applicable`   — the exercise is not a strength exercise. `sets`/`reps`/
   `weight_kg` cannot express distance or duration, so no honest load or rep
   trend exists for cardio/mobility work.
2. `insufficient_data` — fewer than `MIN_SESSIONS_FOR_TREND` sessions.
3. `possible_plateau` — the most recent `PLATEAU_MIN_SESSIONS` sessions span at
   least `PLATEAU_MIN_SPAN_DAYS` days and both the primary metric and session
   volume stay inside a +/-`MIN_MEANINGFUL_CHANGE_PCT` band. Checked *before*
   `progressing` so that an older jump followed by four flat sessions is still
   reported as a plateau. The day-span requirement is what stops four sessions
   logged in one week from being labelled a plateau.
4. `progressing`      — the primary metric rose by more than
   `MIN_MEANINGFUL_CHANGE_PCT` across the window, and session volume did not
   fall by more than that same threshold. (Load up but volume meaningfully down
   is a trade-off, not progress — that falls through to `stable`.)
5. `declining`        — the primary metric fell by more than the threshold.
6. `stable`           — everything else.

The *primary metric* is the session's top load when the exercise has at least
`MIN_SESSIONS_FOR_TREND` weighted sessions in the window, otherwise total reps.
Weighted volume (`sets x reps x weight`) is only computed for rows that actually
carry a weight, and is never used as the primary metric on a reps basis.
"""

from __future__ import annotations

import enum


class TrendClassification(enum.StrEnum):
    """The complete, closed set of trend verdicts the engine can produce."""

    PROGRESSING = "progressing"
    STABLE = "stable"
    POSSIBLE_PLATEAU = "possible_plateau"
    DECLINING = "declining"
    INSUFFICIENT_DATA = "insufficient_data"
    NOT_APPLICABLE = "not_applicable"


class MetricBasis(enum.StrEnum):
    """Which metric the classification was actually computed from."""

    LOAD = "load"
    REPS = "reps"
    NONE = "none"


class SuggestionKind(enum.StrEnum):
    """The complete, closed set of next-session suggestions."""

    MAINTAIN = "maintain"
    SMALL_PROGRESSION = "small_progression"
    REVIEW_EXERCISE = "review_exercise"
    INSUFFICIENT_HISTORY = "insufficient_history"
    CONSULT_PROFESSIONAL = "consult_professional"


# --- Trend classification ------------------------------------------------------

# How many of the most recent sessions are considered. Older sessions are visible
# in the history endpoint but do not influence the verdict.
TREND_WINDOW_SESSIONS = 5

# Below this, the engine refuses to classify rather than guess from two points.
MIN_SESSIONS_FOR_TREND = 3

# A plateau claim needs both more sessions and more calendar time than a trend claim.
PLATEAU_MIN_SESSIONS = 4
PLATEAU_MIN_SPAN_DAYS = 14

# Percentage change below which a difference is treated as noise rather than a
# trend. 2% is roughly one 1 kg jump on a 50 kg lift — smaller than the smallest
# plate change most users can actually make.
MIN_MEANINGFUL_CHANGE_PCT = 2.0

# Weights are stored as double precision. Two logged weights are "the same load"
# when they differ by less than this. Used for equality, never for the percentage
# comparisons above, which carry their own threshold.
WEIGHT_COMPARISON_EPSILON_KG = 1e-6


# --- Workload and consistency --------------------------------------------------

# "This week" vs "last week" for both the volume and consistency comparisons.
CONSISTENCY_WINDOW_DAYS = 7

# How far back `average_workouts_per_week` and `active_weeks` look.
CONSISTENCY_TREND_WEEKS = 8


# --- Query bounds --------------------------------------------------------------

# Single-exercise history: a year is enough to show a real training arc while
# keeping the row count bounded for a heavy user.
HISTORY_LOOKBACK_DAYS = 365

# Cap on rows returned by the history endpoint.
HISTORY_MAX_SESSIONS = 50

# The overview joins every exercise at once, so it uses a tighter window.
OVERVIEW_LOOKBACK_DAYS = 180

# Exercises shown on the overview, most recently trained first.
OVERVIEW_MAX_EXERCISES = 8


# --- Progression suggestions ---------------------------------------------------

# Deliberately conservative: a single-digit-percent nudge, hard-capped in
# kilograms, and only ever offered as a suggestion the user chooses to apply.
PROGRESSION_STEP_PCT = 2.5
MIN_LOAD_INCREASE_KG = 0.5
MAX_LOAD_INCREASE_KG = 2.5

# Suggested loads are rounded to this granularity so the number is loadable.
LOAD_ROUNDING_KG = 0.5


# --- Medical boundary ----------------------------------------------------------

# This application is not a medical tool. When a user's free-text workout notes
# mention pain or injury, the engine stops offering training prescriptions for
# that exercise and refers them to a qualified professional instead. The match is
# a deliberately blunt keyword screen — it is a conservative trigger for *not*
# giving advice, never an assessment of what is wrong.
MEDICAL_REFERRAL_KEYWORDS: frozenset[str] = frozenset(
    {
        "pain",
        "painful",
        "hurt",
        "hurts",
        "hurting",
        "injury",
        "injured",
        "strain",
        "strained",
        "sprain",
        "sprained",
        "tear",
        "torn",
        "tendon",
        "tendonitis",
        "tendinitis",
        "physio",
        "physiotherapist",
        "doctor",
        "surgery",
        "fracture",
    }
)

MEDICAL_REFERRAL_MESSAGE = (
    "Your notes for this exercise mention pain or injury, so no training "
    "suggestion is offered here. Please speak to a doctor, physiotherapist or "
    "another qualified professional before changing your training."
)


def mentions_medical_concern(*texts: str | None) -> bool:
    """True when any note appears to mention pain or injury.

    Pure and case-insensitive. Word-boundary matching keeps "training" from
    matching "strain". Callers pass only the resulting boolean onward — the note
    text itself never leaves the database layer.
    """
    for text in texts:
        if not text:
            continue
        words = {
            word.strip(".,!?;:()[]\"'").lower() for word in text.split()
        }
        if words & MEDICAL_REFERRAL_KEYWORDS:
            return True
    return False
