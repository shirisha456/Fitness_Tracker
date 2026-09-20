# Java-only cleanup report

The repository is now a Java 21 / Spring Boot application. The Python/FastAPI implementation
that preceded it has been removed from the active tree and preserved in git history.

## 1. What existed before cleanup

| Location | Files | Classification |
|---|---|---|
| `backend/` | 87 tracked | FastAPI app, Alembic migrations, Celery tasks, 234 pytest tests, Dockerfile, entrypoint |
| `contract/` | 8 | 4 Python generators/differ + 4 checked-in data files |
| `docker-compose.yml` | 3 services | `api`, `worker`, `beat` |
| `.github/workflows/ci.yml` | 1 job | setup-python, ruff, pytest |
| `run_all_tests.ps1`, `run_verify.ps1` | 2 | pytest runners with hardcoded `C:\Users\Shirisha\...` venv paths |
| `perf/run.sh` | — | accepted a `python` target |
| `.env.example` | 2 vars | SQLAlchemy `DATABASE_URL`, `CELERY_TASK_ALWAYS_EAGER` |
| `.pytest_cache/` | — | stray artifact at repo root |
| docs | ~17 files | migration-era and phase write-ups |

**Classification:**

- **Active runtime** — the three compose services, `backend/Dockerfile`, `entrypoint.sh`,
  nginx's switchable upstream, the asyncpg `DATABASE_URL`.
- **Parity/migration tooling** — `contract/*.py`, `test_training_fixture_parity.py`. All
  required a *running* Python backend or interpreter.
- **Tests only** — `backend/tests/`, `conftest.py`, pytest/ruff config, the CI job, both
  `.ps1` scripts.
- **Documentation only** — ADRs 003/004, phase write-ups, case study.
- **Dead** — `.pytest_cache/`, `backend/.venv/`.

### The finding that shaped the plan

Four Java test classes read files from `contract/`:

| Test | Reads |
|---|---|
| `TrainingAnalyticsFixtureTest` | `training-insights-fixtures.json` |
| `JwtCompatibilityTest` | `jwt-fixtures.json` |
| `Argon2CompatibilitySpikeTest` | `argon2-fixtures.json` |
| `SchemaParityTest` | `expected-schema.txt` |

These are **static data, not Python execution** — but deleting `contract/` wholesale would
have broken the Java build. The data was moved into the Java module; the generators were
deleted.

A second finding mattered more: **`backend-java/`, `contract/`, `perf/` and `monitoring/`
were entirely untracked** — zero tracked files. Deleting `backend/` and committing would have
left the repository with no backend at all.

## 2. What was deleted

```
backend/                          87 files — FastAPI app, Alembic, Celery, pytest suite
contract/                          8 files — parity differ, fixture generators, data files
run_all_tests.ps1, run_verify.ps1  2 files — pytest runners
.pytest_cache/                              — stray artifact
```

**97 files deleted, 14,797 lines removed.** Zero `.py` files remain in the repository.

## 3. Infrastructure changed

| File | Change |
|---|---|
| `docker-compose.yml` | Removed `api`, `worker`, `beat`. nginx `depends_on` no longer references `api`. nginx mounts a plain conf again — the envsubst template existed only to switch between two backends. |
| `docker-compose.prod.yml` | Same: `API_UPSTREAM` variable removed, plain conf mount restored. |
| `nginx/nginx.conf`, `nginx.prod.conf` | `upstream api_backend { server api-java:8000; }` — hardcoded, no fallback route. |
| `.env.example` | `DATABASE_URL` is now JDBC. `CELERY_TASK_ALWAYS_EAGER` removed. The cutover block replaced with a single BFF note. |
| `.github/workflows/ci.yml` | Python job removed; the Java job is now `backend`. |
| `perf/run.sh` | No longer takes a backend argument. |
| `monitoring/prometheus.yml` | Stale "the Python backend exposes no metrics" note removed. |
| `.gitignore` | Python entries removed; oversized rejected-stress logs excluded. |

The application image is `eclipse-temurin:21-jre-jammy` and **contains no Python
interpreter** — verified by `command -v python3 || command -v python` inside the running
container, which returns nothing.

## 4. Documentation changed

**Rewritten as current:**
- `README.md` — now leads with "Adaptive Fitness Platform — a Java 21 / Spring Boot fitness
  platform". Stack, architecture diagram and service table describe only the current system.
- `docs/architecture.md` — rewritten for the Java module layout and the current request flow.
- `docs/rollback.md` — rollback is now *current Java image → previous Java image*. The old
  Java↔Python model appears only under an explicit "Historical note".
- `backend-java/README.md` — module README rather than a migration status page.

**Labelled historical** (banner naming the tag and branch): `java-migration-plan.md`,
`java-schema-compatibility.md`, `api-compatibility.md`, `final-report.md`, `case-study.md`,
`phase1.md`–`phase16.md`, ADR 003 and ADR 004 (both additionally marked **Superseded**).

**Added:** `docs/migration-validation.md`, preserving the 64/64 parity result, the
cross-language compatibility numbers and the cutover evidence.

**Renamed:** `PythonNumbers` → `AnalyticsNumbers` (27 references). 144 Python-referencing
phrases across 63 Java files were reworded from present tense to historical or neutral.

## 5. Tests run, and results

| Gate | Result |
|---|---|
| `./mvnw verify` | **269 tests, 0 failures** |
| End-to-end against the Java-routed stack | **75/75** |
| Frontend lint | clean |
| Frontend build | compiles |
| Docker health | 8 services, all healthy or running |
| Secret scan | no keys; `.env` ignored; `OPENAI_API_KEY` empty in `.env.example` |

The end-to-end suite covers health and readiness, registration and login, refresh rotation
with reuse detection and family revocation, workouts, nutrition, progress, profile, training
insights, AI-unavailable degradation, the Redis Streams email path into Mailhog, Prometheus,
and every frontend page and BFF route. It additionally asserts that `api`, `worker` and
`beat` are **absent** and that the image has no Python interpreter.

AI-enabled mode was validated separately against a local stub: `/ai/recommendations`,
`/ai/generate-workout`, `/ai/generate-meals` and `/ai/chat` all 200, empty chat 400, and
`explain=true` returning `explanation_source: "ai"` with the classification unchanged.

## 6. Docker and container health

```
api-java     Up (healthy)     Spring Boot, the only backend
db           Up (healthy)     PostgreSQL 16
frontend     Up (healthy)     Next.js BFF
redis        Up (healthy)     Streams queue
nginx        Up               routes /api/v1/* to api-java
prometheus   Up               scrapes api-java:9000
grafana      Up               10-panel dashboard, datasource resolved
mailhog      Up               captured mail
```

## 7. nginx routing proof

Not asserted from configuration — measured from the server's own counters:

```
api-java /api/v1/workouts request counter: 44 -> 51   (delta 7, from 7 requests via nginx:80)
nginx upstream in the running container:   server api-java:8000;
containers that could serve /api/v1:       1
```

## 8. Redis and email validation

- Stream `fitness:email` present; dead-letter length **0**; retry queue size **0**.
- A registration through nginx produced a verification email in Mailhog, and
  `email_sent_total` incremented.
- Retry/backoff and dead-letter behaviour are covered by 8 tests in `EmailDispatchTest`,
  including that a failed send waits out its backoff and that a dead-lettered message keeps
  the recipient but **not** the one-time token.

## 9. Frontend validation

Register and login through the BFF, both session cookies set, unauthenticated `/dashboard`
returning 307, and all seven pages returning 200 **with their own content asserted** — a
redirect to the login page cannot pass as a dashboard. BFF routes `/api/workouts`,
`/api/auth/me`, `/api/training/exercises/{id}/insights` and logout all 200.

## 10. Observability validation

Prometheus target `fitness-tracker-java` is `up`; latency histogram buckets are exported;
the Grafana dashboard provisions with 10 panels and a resolved datasource; and a scan of the
full metrics exposure for UUID- or email-shaped strings returns **zero** — no user data in
labels.

## 11. Remaining Python references, and why

**15 occurrences across 4 files**, all in `backend-java/`:

| File | Why it stays |
|---|---|
| `AnalyticsNumbers.java` (9) | Documents that rounding is half-to-even over the *exact binary value* and that `Math.round` and `BigDecimal.valueOf` both disagree. Naming CPython is what makes the unusual `new BigDecimal(double)` spelling explicable. |
| `TrainingAnalytics.java` (3) | Same: two `str.capitalize()` / banker's-rounding notes and one pointer to `AnalyticsNumbers`. |
| `TrainingAnalyticsFixtureTest.java` (2) | Notes that evidence strings carry `:g` and `:+` formatting, which is why they are compared as strings. |
| `ContractFixtures.java` (1) | The deliberate historical note explaining where the fixtures came from and where to find the generators. |

These name a **language's numeric semantics**, not a dependency. Removing them would delete
the reason the code is written the way it is.

Elsewhere: `frontend/package-lock.json` contains "Python" inside a transitive dependency's
metadata, and several `.tsx` files match a case-insensitive search for `pip` only because
they contain `apiPath`. Neither is a Python reference.

## 12. Risks and limitations

- **No frontend test suite.** `package.json` defines only `dev`, `build`, `start`, `lint`.
  The 75 scripted checks are the frontend's entire regression coverage.
- **The fixtures are now opaque data.** The generators are gone, so regenerating them means
  checking out `pre-java-only-cleanup`. If the intended behaviour ever changes, the fixtures
  must be edited by hand or regenerated from history.
- **Actuator is protected by network isolation, not authentication.**
- **Grafana ships with `admin/admin`**, bound to loopback. Set `GRAFANA_PASSWORD` before
  exposing it.
- **Benchmarks are unchanged and were not re-run.** The cleanup removed no code on any
  request path — only comments, documentation and dead services — so the existing figures
  still describe this build. They remain local comparative measurements, not capacity.
- **`alembic_version` still exists in deployed databases.** It is harmless and is deliberately
  left alone: Flyway is baselined on top of it, and dropping it would break nothing but gains
  nothing.
- **Single instance, single region, no autoscaling, no read replicas.**

## 13. Final architecture

```
Browser
   │
   ▼
nginx  :80/:443
   ├── /api/v1/*  ────────────────┐
   └── /, /api/*  ──▶ Next.js BFF ┤  (server-side, httpOnly cookies)
                                  ▼
                   Java 21 / Spring Boot  (api-java)
                   auth · workouts · nutrition · progress
                   profile · training insights · AI
                                  │
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                   ▼
       PostgreSQL 16          Redis 7            AI provider
        (Flyway)              Streams             (optional)
                                  │
                                  ▼
                        EmailWorker (in-process)
                        retry · backoff · dead-letter

       Actuator :9000 (unpublished) ──▶ Prometheus ──▶ Grafana
```

There is no Python in this diagram, in the compose file, in the images, or on any request
path.

## 14. Preservation

| Ref | Contents |
|---|---|
| tag `pre-java-only-cleanup` | The repository immediately before this cleanup |
| branch `legacy-python-backend` | The complete FastAPI implementation — 87 files including `training_insights` and the 8-file parity harness |

Git history was not rewritten.
