# ADR 006 — Training analytics are computed deterministically; the LLM only explains

## Status
Accepted.

## Context
The adaptive-training feature has to answer questions like "is my bench press
progressing?" and "has my squat stalled?" from a user's logged workout history.

There are two obvious ways to build it:

1. **Send the history to the LLM and ask.** Cheap to build — one prompt, one
   response. The model reads the sessions and states a verdict.
2. **Compute the verdict in code, and use the LLM only to word it.**

Option 1 is tempting because the repository already has a working OpenAI
integration. It fails on four counts:

- **It is unverifiable.** "You increased volume 7% this week" cannot be checked
  against anything. The model is as likely to produce that sentence from four
  identical sessions as from a real increase.
- **It is untestable.** There is no assertion to write. Mocking the model means
  testing the mock; not mocking it means a paid, flaky, non-deterministic test.
- **It is unstable.** The same history can yield "progressing" on Monday and
  "plateaued" on Tuesday, with nothing in the data having changed.
- **It couples a core feature to a third-party outage.** No API key, no insights.

## Decision
Two layers, with a hard boundary between them.

**Layer 1 — deterministic analytics.** `modules/training_insights/analytics.py`
computes every metric and every classification from structured rows using explicit
rules, with all thresholds named in `rules.py`. The functions are pure: no database,
no clock, no network. The verdict vocabulary is a closed enum — `progressing`,
`stable`, `possible_plateau`, `declining`, `insufficient_data`, `not_applicable`.
Each result carries `sessions_analyzed`, a `date_range` and an `evidence[]` list
built from the actual numbers.

**Layer 2 — explanation.** The LLM receives the already-computed summary (~440
characters of derived numbers and the verdict) and restates it in one or two
sentences. It is instructed not to calculate and not to introduce a number that is
not in the analysis. It never sees the raw history and never sees the user's
free-text notes.

The boundary is enforced structurally, not by convention: the insight is fully built
before the `explain` flag is read, and the AI call can only overwrite `explanation`
and `explanation_source`. `explain` defaults to false, so the normal path never
touches OpenAI at all.

## Consequences
- **The analytics are testable.** 53 unit tests assert exact verdicts on exact
  inputs, with no database and no mocking — including boundary cases like 100 → 102 kg
  (exactly the 2.0% threshold) classifying as `progressing` while 100 → 101.9 kg does
  not. That is not a test one can write against an LLM.
- **Every recommendation is auditable.** The UI shows the evidence under the verdict,
  so "why did the system say this?" is answered on the page rather than by trusting a
  model.
- **AI availability is a presentation concern, not an availability concern.** With no
  API key, an expired key, a rate limit or an upstream outage, the feature returns the
  same numbers with deterministic prose and HTTP 200. Verified end to end in both
  configurations.
- **Thresholds became an explicit design surface.** Choosing 2%, 4 sessions and 14
  days forced defensible answers to questions the LLM approach would have left
  implicitly and invisibly decided. They are constants precisely because they are
  judgement calls that may need to move.
- **The cost is real.** The rules have to be written, argued over and maintained, and
  they will sometimes disagree with a knowledgeable coach — a plateau by these rules
  is not necessarily a plateau in a periodised programme. The accepted trade is that a
  rule that is wrong can be found, discussed and changed; a model that is wrong cannot.
- **The LLM is not removed** — the existing AI coach still generates workouts, meal
  ideas and chat, and now receives these deterministic signals as context instead of a
  bare workout count.
