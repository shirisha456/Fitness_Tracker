# Historical migration validation

> **Historical.** This document records how the Java backend was validated during the
> rewrite from an earlier Python/FastAPI implementation. That implementation is no longer
> part of the architecture, is not deployed, and is not a rollback target. It is kept in git
> history only: tag `pre-java-only-cleanup`, branch `legacy-python-backend`.

## Why this is worth keeping

The rewrite's central claim was that the new backend was a faithful replacement. That claim
was tested rather than asserted, and the evidence is the reason the cutover was safe. The
tooling that produced it has been removed along with the implementation it compared against;
the results are recorded here.

## Response parity

A differ ran the same request sequence against both backends and compared status codes and
JSON bodies field by field, ignoring only genuinely volatile keys (ids, timestamps, tokens,
correlation ids).

**Final result: 64/64 identical responses**, across seven suites:

| Suite | What it covered |
|---|---|
| `workouts` | Exercise library, workout CRUD, ownership enforcement |
| `nutrition` | Meals, water entries, daily summary |
| `progress` | Body measurements, goals |
| `profile` | Create, full replace, null-out semantics |
| `training` | All six classifications, metrics, evidence strings |
| `ai` | Generation, chat, recommendations, explanation source |
| `malformed` | Absent parameters, wrong types, bad identifiers, unknown routes |

Verified in two configurations — with an AI provider configured and with none — because the
degradation path is part of the contract.

## Cross-language compatibility

| Check | Result |
|---|---|
| Java accepts tokens minted by the previous implementation | 4 tests, 12 fixture tokens |
| The previous implementation accepts Java-minted tokens | 15/15 checks |
| Argon2id parameters match the existing hash corpus | 4 tests, 4 fixtures |
| Deterministic analytics agree numerically | 92 assertions on both sides, from one shared fixture file |

The analytics fixtures pinned Python's `round()` half-to-even semantics over the exact binary
double, which only `new BigDecimal(double).setScale(n, HALF_EVEN)` reproduces in Java. That
was measured before any code was written, not discovered afterwards.

## Cutover

| Step | Result |
|---|---|
| Post-cutover validation against the Java-routed stack | 72/72 |
| Frontend flows driven against Java | 85/85 |
| Routing proof | 5 requests through nginx incremented the Java service's request counter by exactly 5; the other backend's access log recorded 0 |

## What survives in the current codebase

The fixture *data* is still used, because it still guards real behaviour — rounding
boundaries, JWT claim shape, Argon2 parameters and database schema drift. It lives at
`backend-java/src/test/resources/contract/` and is consumed by four Java tests. The
generators that produced it are gone; see `ContractFixtures` for why the data is kept.

## What was removed

- The response-parity differ, which required both backends running
- The fixture generators
- The Python test suite (234 tests) and its CI job

These required the Python implementation to remain runnable. Keeping an entire backend alive
so that a comparison harness could keep running is not a good reason to keep a backend alive.
