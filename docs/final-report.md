# Fitness Tracker — Final Report

**Status: complete.** Java/Spring Boot is the active backend. The Python/FastAPI service is
retained as a documented rollback target and behavioural reference.

---

## 1. Recruiter-friendly project summary

Fitness Tracker is an **Adaptive Fitness Platform**: a full-stack web application where
people log workouts, meals and body measurements, and get personalised training feedback
based on their own history. It analyses your logged sessions to tell you whether a lift is
progressing, stalling or declining — and shows the numbers behind every verdict rather than
just asserting it.

The backend is **Java 21 / Spring Boot**, backed by PostgreSQL and Redis, with a Next.js
frontend. It was originally built in Python and later rewritten in Java, with automated
checks proving the new backend returned identical responses before it was switched on.

---

## 2. Final architecture

```
                    ┌──────────────┐
  Browser ─────────▶│    nginx     │  :80 / :443, TLS via Let's Encrypt
                    └──────┬───────┘
                           │  /api/v1/*                    /  and  /api/*
                           ▼                                      ▼
                 ┌───────────────────┐                  ┌──────────────────┐
                 │  api-java         │◀─────────────────│  Next.js (BFF)   │
                 │  Spring Boot 3.5  │  server-side      │  App Router      │
                 │  Java 21          │  calls            │  httpOnly cookies│
                 └─────┬───────┬─────┘                  └──────────────────┘
                       │       │
          ┌────────────┘       └─────────────┬──────────────────┐
          ▼                                  ▼                  ▼
   ┌─────────────┐                   ┌──────────────┐   ┌───────────────┐
   │ PostgreSQL  │                   │    Redis     │   │  AI provider  │
   │     16      │                   │      7       │   │  (OpenAI API) │
   │  Flyway     │                   │ Streams queue│   │  bounded +    │
   └─────────────┘                   └──────┬───────┘   │  timed out    │
                                            │           └───────────────┘
                                            ▼
                                   ┌──────────────────┐
                                   │  EmailWorker     │  in-process consumer
                                   │  XREADGROUP/XACK │  retry + dead-letter
                                   └──────────────────┘

   Observability:  api-java :9000/actuator/prometheus ──▶ Prometheus ──▶ Grafana
   Legacy/rollback: api (FastAPI) + worker + beat (Celery), same DB/Redis/SECRET_KEY
```

### Synchronous request flows

Everything a user waits for:

- **Browser → nginx → Next.js** for pages; Next.js Route Handlers hold JWTs in httpOnly
  cookies (`ff_access` path `/`, `ff_refresh` path `/api`) and call the backend server-side.
- **Browser → nginx → api-java** for direct API calls (`/api/v1/*`).
- **api-java → PostgreSQL** for all reads and writes, through HikariCP.
- **api-java → AI provider** for generation, chat and explanation. Bounded by an explicit
  timeout and a semaphore that **rejects rather than queues**, and never inside a database
  transaction.

### Asynchronous processing

Everything the user does **not** wait for:

- **Transactional email.** Registration and password reset `XADD` a message to a Redis
  Stream and return immediately. If Redis is unreachable the enqueue is logged and the
  request still succeeds — email is the non-essential half of both flows.
- **`EmailWorker`**, running inside the API process, consumes via `XREADGROUP`, acks only
  after the send returns, reclaims entries from dead consumers with `XAUTOCLAIM`, retries
  failures through a sorted-set delay queue with exponential backoff, and dead-letters
  poison messages after `max-attempts`.

A separate deployable was not introduced for two email types; the migration rule was that
new infrastructure needs a real reason.

### Deterministic training analytics

`TrainingAnalytics` is a pure domain class with no I/O and no provider calls. It computes
six classifications — `progressing`, `stable`, `possible_plateau`, `declining`,
`insufficient_data`, `not_applicable` — plus consistency and volume metrics and the evidence
strings shown in the UI. **The LLM never participates in a calculation.** When
`?explain=true` is passed, the model is given the already-computed insight and may rewrite
only the prose; the response carries `explanation_source: "ai" | "deterministic"` so a
caller always knows which it got.

### Supporting infrastructure

- **PostgreSQL 16** — schema owned by Flyway, `ddl-auto=validate`, never `update`.
- **Redis 7** — Streams queue for Java; also the Celery broker for the legacy backend.
- **Prometheus + Grafana** — scrapes Actuator on port 9000, which is **not published to the
  host**; both UIs bind to `127.0.0.1`.
- **nginx** — TLS termination and path routing; the API upstream is a variable so cutover
  and rollback are a restart, not an edit.
- **Docker Compose** — one stack for both backends side by side.
- **CI** — GitHub Actions running lint, test and build.

---

## 3. Major engineering work

**FastAPI → Spring Boot migration.** Built side by side over 15 phases, with the Python
service as the executable specification. Java was never routed traffic until its responses
matched.

**API and schema compatibility.** All 46 endpoints reproduced with identical envelopes,
snake_case field names, status codes and error shapes. 12 tables mapped onto the existing
schema with no migration. `Double` rather than `BigDecimal`, because that is what the
Python service already stored.

**Cross-language JWT compatibility.** Both services sign with the same `SECRET_KEY` and
accept each other's tokens — the property that makes cutover and rollback non-disruptive.
Verified in both directions: Java validating Python-minted fixtures, and
`contract/verify_java_jwts.py` checking Python accepts Java-minted tokens (15/15).

**Argon2 compatibility.** Parameters pinned to m=65536, t=3, p=4, hashLen=32, saltLen=16 to
match argon2-cffi's defaults, so the existing password hash corpus keeps working. Spring's
defaults are weaker and would have silently rejected every existing user.

**Refresh-token rotation and reuse detection.** Presenting an already-rotated token revokes
the entire token family. The revocation must survive the rejection, which needs
`REQUIRES_NEW` **on a separate bean** — a proxy self-invocation would have made it a no-op.

**PostgreSQL native enums.** `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` passes schema validation
and then fails on every insert, because it binds `Enum.name()` (uppercase) against lowercase
labels. Solved with a custom Hibernate `UserType` binding `Types.OTHER` plus
`columnDefinition`.

**Flyway baseline strategy.** V1 builds fresh databases; an existing Alembic database is
baselined so V1 is never replayed, and `alembic_version` is preserved so the Python service
still works. Re-proved during the session when the dev database had to be rebuilt.

**Adaptive analytics migration.** Rounding behaviour was measured before any code was
written — CPython's `round()` is half-to-even over the exact binary double, which only
`new BigDecimal(double).setScale(n, HALF_EVEN)` reproduces. Language-neutral fixtures
generated from the Python engine are consumed by both pytest and a JUnit
`@ParameterizedTest`.

**AI failure isolation.** A typed provider boundary maps unavailability to 503 and bad model
output to 502. `AiService` is deliberately not `@Transactional`. `TrainingInsightExplainer`
catches provider failures and returns the insight unchanged. No automated test can reach the
paid API — tests bind a fake provider.

**Celery → Redis Streams.** Replaced with at-least-once delivery, crash recovery, backoff
retries and a dead-letter stream — stronger than the Celery setup it replaced, which acked
before execution and never retried.

**Observability.** Actuator on an unpublished management port, percentile *histograms*
rather than pre-computed quantiles (so Prometheus can compute p95/p99 over any window), and
a 10-panel dashboard. No user ID, workout ID, email or free-text exercise name appears in
any label.

**Java cutover and rollback.** Verified by counter, not assertion, and rollback exercised in
both directions with accounts proven portable across backends.

---

## 4. Testing and correctness

Final numbers, from the last run of each suite:

| Suite | Result |
|---|---|
| Java (`./mvnw verify`) | **269 tests, 0 failures** |
| Python regression (`pytest`) | **234 passed** |
| Cross-backend response parity | **64/64 identical**, 7 suites |
| Post-cutover validation (Java-routed) | **72/72** |
| Frontend flows against Java (pre-cutover sweep) | **85/85** |
| JWT compatibility — Java accepts Python tokens | 4 tests, 12 fixture tokens |
| JWT compatibility — Python accepts Java tokens | **15/15 checks** |
| Argon2 compatibility | 4 tests, 4 fixtures |
| Training analytics fixture parity | **92 pytest ↔ 92 JUnit**, same file |
| Frontend lint / build | clean / compiles |
| `ruff check` | clean |

Parity suites: `workouts`, `nutrition`, `progress`, `profile`, `training`, `ai`, `malformed`.

**There is no frontend unit-test suite** — `package.json` defines only `dev`, `build`,
`start`, `lint`. The 72 post-cutover checks and the 85-flow sweep are the frontend's entire
regression coverage.

---

## 5. Real defects discovered

All four were found by testing or performance work, not by review, and all were in the new
Java code.

**1. Database connection held across Argon2 work.** `AuthService.login` was `@Transactional`,
so it acquired a pooled connection before the password check and held it through ~100ms of
Argon2id CPU that touches no database — capping concurrent logins at the pool size and
starving every other endpoint. *Fixed* by removing the annotation and moving the token write
into `TokenIssuanceService`, so the connection is held only for the queries.

**2. Transactional proxy self-invocation.** `login` called `this.issueTokenPair(...)`, and a
self-invocation never crosses the Spring proxy, so that method's `@Transactional` did
nothing; the write succeeded anyway via the repository's own transaction, which is exactly
why it went unnoticed. *Fixed* by extracting it to a separate bean so the annotation applies.

**3. Missing query parameters returned 500.** Nothing handled
`MissingServletRequestParameterException`, so `GET /nutrition/summary` with no `date` and
`GET /auth/verify-email` with no `token` fell through to the catch-all — 8,720 HTTP 500s in
one load-test run, where Python correctly returns 400. *Fixed* with a dedicated handler
reproducing FastAPI's `query.<name>` / "Field required" shape, plus a new `malformed` parity
suite, because every request the old suites compared was well-formed.

**4. Email retry and dead-letter token lifecycle.** Failed sends were re-added to the stream
immediately, so a 1s poll burned all five attempts in ~5 seconds — no defence against a
briefly unreachable mail host — and dead-lettered messages retained the raw single-use token
in a stream nothing ever drains. *Fixed* with a sorted-set delay queue (exponential backoff,
10s → 10m cap) and by redacting the token before dead-lettering, keeping the recipient.

---

## 6. Performance methodology

### What this is and is not

All benchmarks ran **locally on a single machine**, with the load generator, both backends,
PostgreSQL, Redis, Prometheus and Grafana **sharing the same CPU and memory**. The numbers
characterise a relative change on that machine.

**None of these figures is a production capacity statement.** No claim is made about how many
requests per second this application would serve on dedicated infrastructure.

### Environment

| | |
|---|---|
| Host | Darwin arm64, 10 CPUs, 16 GB |
| Docker | 10 CPUs, 7 GB |
| Load generator | k6 0.53.0, containerised, 4 CPUs |
| Backend | `api-java`, Spring Boot 3.5.3, Java 21, virtual threads |
| Database | PostgreSQL 16, `max_connections=100`, shared with the legacy stack |
| Dataset | 100 users · 10,000 workouts · 40,000 workout-exercises · 30,000 meals · 6,000 measurements · 100 profiles |
| Commit | `3111596` (working tree dirty) |
| Date | 2026-09-19 |

### Why k6 rather than Gatling

Gatling is the more natural fit on paper — a JVM tool for a JVM service, with a Maven plugin
and a typed DSL. It was rejected because the generator and the service share one laptop:
Gatling would put a second JVM, with its own heap and collector, beside the one being
measured. k6's VUs are goroutines with a far smaller footprint and an explicit CPU limit.

### Why the ~5,000 req/s stress results were rejected

The stress profile appeared to show a dramatic regression after a change. Before reporting
it, the **unchanged** configuration was re-run on the same machine — and performed *worse
still* (86 req/s versus the original 1,407 req/s). The host had degraded across successive
5-minute runs, with 46,694 TCP "connection refused" errors appearing mid-sequence: the
kernel was refusing connections, so those runs measured the laptop, not the application.

An unreproducible measurement cannot support a before/after claim, so every 5,000 req/s
figure was discarded. Had that check been skipped, the obvious conclusion would have been
confidently wrong. The artifacts are retained in `perf/results/` for provenance and are
**clearly labelled `stress-*`, never cited as results.**

### The reproducible workloads

**Baseline** (`perf/k6/load-test.js`) — arrival-rate ramp 20 → 300 req/s over 4m45s, plus a
constant 3 req/s login scenario; ~56,000 requests. Reproducible:

| Run | Throughput | Failed | p50 | p95 | p99 |
|---|---|---|---|---|---|
| baseline | 197.61 req/s | 0.000% | 0.92 ms | 1.97 ms | 106.99 ms |
| baseline recheck | 197.45 req/s | 0.000% | 0.92 ms | 2.34 ms | 111.07 ms |

Nothing saturated at this level: HikariCP pending peaked at **0**, active at 6 of 10, process
CPU at 17.8%. The aggregate p99 of ~107ms is entirely the login scenario — every read
endpoint's p99 was under 4.2ms, while login's was 273ms, and login is ~1.6% of requests,
placing it exactly at the p99 boundary.

**Login contention** (`perf/k6/login-contention.js`) — the final before/after benchmark. A
constant **8 logins/s alongside 200 reads/s for 90 seconds**, deliberately below the CPU
ceiling so that what is measured is contention for database connections rather than Argon2
throughput. ~18,720 requests per run, connection pool pinned to **10 on both sides**, so the
only variable is the `@Transactional` annotation.

---

## 7. Performance optimisation

### The bottleneck

`AuthService.login` was `@Transactional`. Spring acquires a pooled connection when the
transaction opens and releases it when the method returns — so the connection was held across
Argon2id verification, roughly 100 ms of pure CPU that issues no SQL.

A connection held for ~100 ms instead of ~1 ms is a hundredfold reduction in what that pool
slot can serve. Evidence from the saturation run, read from the server's own metrics:

| Signal | Value |
|---|---|
| HikariCP threads pending | 3,285 |
| HikariCP active | 10 (= pool max) |
| Acquisition timeouts | 3,775 |
| Worst-case acquisition | 30.9 s |
| Read endpoints' server-side p95 | ~860 ms (on ~1 ms of real query work) |

### The change

`login` is no longer `@Transactional`: the user lookup runs in the repository's own short
read transaction, Argon2 runs holding nothing, and the token write opens its own transaction
in `TokenIssuanceService` — a separate bean, so the annotation actually applies. The pool was
also sized explicitly to 24 and acquisition failure mapped to 503.

### Measured result

Same benchmark, same dataset, same machine, pool pinned to 10 on both sides:

| Metric | BEFORE | AFTER | Change |
|---|---|---|---|
| **READ p95** | **4.03 ms** | **2.41 ms** | **−40.1%** |
| **READ p99** | **9.92 ms** | **6.06 ms** | **−38.9%** |
| READ p50 | 1.23 ms | 1.10 ms | −11.0% |
| LOGIN p95 | 392.79 ms | 353.53 ms | −10.0% |
| LOGIN p99 | 438.83 ms | 367.46 ms | −16.3% |
| Throughput | 206.6 req/s | 206.6 req/s | unchanged |
| Error rate | 0.000% | 0.000% | unchanged |
| Requests | 18,722 | 18,723 | — |

Percentages verified against `perf/results/login-BEFORE/summary.json` and
`perf/results/login-AFTER/summary.json`.

Throughput is unchanged because the benchmark is rate-controlled — the offered load is fixed,
so the improvement shows up as latency, which is the honest place for it to appear.

Login remains ~350 ms because Argon2 is *meant* to cost that. **That cost is not a bug and
must not be optimised away** — reducing it means weakening password hashing.

---

## 8. Resume bullets

- Rewrote a production-style fitness application's backend from Python/FastAPI to **Java 21
  and Spring Boot**, validating the new service against the original with an automated
  response-comparison harness that confirmed **64/64 identical API responses** before cutover.

- Built an **adaptive training engine** that analyses a user's workout history to classify
  each exercise as progressing, stalling or declining, with every verdict computed
  deterministically and backed by the numbers behind it; the AI layer only rephrases results
  and never calculates them.

- Diagnosed a database connection-pool bottleneck with **Prometheus and k6 load testing** —
  login was holding a PostgreSQL connection during ~100 ms of password-hashing CPU work —
  and restructured the transaction boundary, cutting read latency under login load from
  **4.03 ms to 2.41 ms at p95 (−40%)** with no change in error rate.

- Implemented **JWT refresh-token rotation with reuse detection**, Argon2id password hashing
  and an at-least-once **Redis Streams** background email pipeline with retry backoff and
  dead-lettering; delivered the migration with a tested rollback path that switches backends
  without any database change.

---

## 9. One-line project description

**Adaptive Fitness Platform — Java/Spring Boot backend that analyses workout history to give
explainable, personalised training feedback.**

---

## 10. Interview preparation

**1. Why migrate from Python to Java?**
The existing service was the specification, so the rewrite was about the target runtime, not
the product: static typing catching contract errors at compile time, a mature transaction and
connection-pooling story, and first-class JVM observability. Be honest in interview — it was
also a deliberate exercise in doing a risky migration safely.

**2. Why Spring Boot?**
Batteries-included for exactly what this app needs: Spring Security for the auth chain, Data
JPA for the existing schema, Flyway, Actuator/Micrometer for metrics, and a mature
`@Transactional` model. The alternative (Quarkus/Micronaut) would have bought startup time
this workload does not care about.

**3. Why a modular monolith instead of microservices?**
One team, one database, one deploy. The modules (`auth`, `workouts`, `nutrition`, `progress`,
`profile`, `traininginsights`, `ai`, `notifications`) are separated by package with no
cross-module entity access — so they *could* be split later — but splitting now would buy
distributed transactions and network failure modes in exchange for nothing.

**4. Redis Streams vs Kafka or RabbitMQ?**
Redis was already deployed as Celery's broker, so Streams added no infrastructure. Kafka or
RabbitMQ would be a new deployable for two email types. Streams provide consumer groups,
acknowledgement and pending-entry recovery — the properties actually needed. Kafka would win
on retention, replay and throughput at a scale this does not have.

**5. How does refresh-token rotation and reuse detection work?**
Every refresh mints a new token and invalidates the old one. Presenting an already-rotated
token means it leaked, so the entire token family is revoked and the user must log in again.
The JWT's `jti` is the primary key of the stored row, so a presented token is a direct lookup.

**6. Why `REQUIRES_NEW` there?**
Reuse detection *rejects* the request, which rolls back its transaction — and the revocation
must survive that rollback, or a leaked token would stay valid. `REQUIRES_NEW` commits the
revocation independently of the failing outer transaction.

**7. What is Spring proxy self-invocation, and where did it bite?**
`@Transactional` is applied by a proxy wrapping the bean. A call to `this.method()` goes
straight to the target and never crosses the proxy, so the annotation silently does nothing.
It bit twice: once in the reuse-revocation design (caught during a risk spike) and once in
`login` calling `this.issueTokenPair(...)` — where the write still succeeded via the
repository's own transaction, which is exactly why it hid. Fix: put the transactional method
on a different bean.

**8. Why Argon2, and why those parameters?**
Argon2id is memory-hard, so it resists GPU and ASIC attacks in a way PBKDF2 and bcrypt do
not. The parameters (m=65536, t=3, p=4) were not chosen for security preference — they had to
match argon2-cffi's defaults exactly, or every existing password hash would have failed to
verify.

**9. How did Flyway coexist with an existing Alembic schema?**
A fresh database gets V1 from Flyway. An existing Alembic database is baselined, so Flyway
records V1 as already applied and never replays it, and `alembic_version` is left untouched so
the Python service still works. Neither tool migrates on rollback — a divergent schema is what
would make rollback impossible.

**10. What went wrong with PostgreSQL native enums?**
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` passes `ddl-auto=validate` and then fails on every
insert, because it binds `Enum.name()` — uppercase — against lowercase database labels. An
`AttributeConverter` with `Types.OTHER` fails differently ("no type mapping for SqlTypes code
1111"). The fix is a custom Hibernate `UserType` binding `Types.OTHER` plus
`columnDefinition`, guarded by a test.

**11. Why Testcontainers rather than H2?**
The application depends on real PostgreSQL behaviour — native enum types, `gen_random_uuid()`,
Flyway baselining. H2 in PostgreSQL-compatibility mode would pass tests the real database
fails, which is worse than having no test.

**12. Why are the analytics deterministic instead of an LLM?**
Because a training verdict has to be reproducible, explainable and identical for identical
input. `TrainingAnalytics` is a pure class with no I/O. The LLM is given the already-computed
result and may only rewrite the prose; the response reports `explanation_source` so callers
know which they received. It also means the feature works with no API key.

**13. How is AI failure isolated?**
Typed exceptions map unavailability to 503 and malformed model output to 502. Calls have
explicit connect and read timeouts, and a semaphore bounds concurrency and **rejects rather
than queues**, so a slow provider sheds load instead of accumulating a backlog. No database
transaction is ever open during a provider call. If explanation fails, the deterministic
insight is returned unchanged.

**14. What do virtual threads actually give you?**
Cheap blocking. They let thousands of requests be in flight without a thread-per-request pool.
They are **not** a timeout, **not** a connection limit and **not** failure isolation — and
that distinction caused the main bottleneck: thousands of virtual threads all queued on a
connection pool sized for ten.

**15. How do you size a connection pool?**
Not "as large as possible". It is bounded by the database's `max_connections` (100 here,
shared with the legacy stack) and by the fact that connections cost server-side resources.
24 was chosen as roughly two per core with headroom for the other services. The more important
lesson: a pool cannot be sized correctly if code holds connections during non-database work.

**16. Describe the performance bottleneck you found.**
`login` was `@Transactional`, so it held a pooled connection across ~100 ms of Argon2 CPU.
Under load that meant 3,285 threads pending on the pool and 30.9 s worst-case acquisition,
with reads doing 1 ms of work reporting 860 ms because almost all of it was waiting. Moving
the write into its own bean and dropping the annotation from `login` cut read p95 by 40%.

**17. Why p95 and p99 rather than an average?**
An average hides the tail that users actually notice. Here it also would have misled in the
opposite direction: the aggregate p99 of ~107 ms looked alarming until decomposed — every read
endpoint was under 4.2 ms and the p99 *was* the login path, which is expensive by design.
Aggregate percentiles across a mixed workload need decomposing before they mean anything.

**18. How did you make the benchmark reproducible?**
By checking. An apparent 6× regression was retested by re-running the **unchanged**
configuration, which performed worse still — proving the host had degraded and the results
measured the laptop. Those runs were discarded. The final benchmark runs below the CPU
ceiling, fixes the dataset, pins the variable being tested, records commit and environment
with every run, and reproduces within noise across repeats.

**19. How did the cutover work, and how would you roll back?**
nginx's API upstream and the BFF's backend URL are environment variables, so cutover is a
container restart. Both services share one database, one Redis and one `SECRET_KEY`, so tokens
minted by either validate against the other — nobody is logged out in either direction.
Rollback was tested Java → Python → Java, with accounts created on one backend proven to log
in on the other.

**20. What would you improve next?**
Front-door concurrency limiting so overload sheds at the edge rather than at the pool; a real
frontend test suite, which does not exist today; moving Actuator behind authentication rather
than relying on network isolation; benchmarking on dedicated infrastructure so capacity could
actually be stated; and eventually retiring the Python service once Java has held traffic long
enough to be boring.

---

## 11. Known limitations

- **No frontend test suite.** `package.json` has no `test` script. Coverage is 72 scripted
  HTTP checks plus lint and build — nothing exercises component behaviour.
- **Benchmarks are single-machine and not capacity figures.** Generator and application shared
  CPU and memory throughout.
- **The saturation regime is not reproducible on this hardware.** Runs above ~1,400 req/s
  degrade the host; those artifacts are retained but unusable as evidence.
- **Actuator is protected by network isolation, not authentication.** Port 9000 is
  unpublished, but anything on the Compose network can read it.
- **Grafana ships with `admin/admin`** and is bound to loopback. `GRAFANA_PASSWORD` must be
  set before exposing it.
- **Validation error prose differs from Python's** (status, `error.code`, `details[].code`
  and `details[].field` all match; the human-readable message does not).
- **Response headers differ** — Java adds Spring Security's defaults. Additive, and documented.
- **Rolling back loses API metrics**: the Python service exposes no Prometheus endpoint.
- **In-flight email does not transfer across a rollback** — the two backends use different
  Redis keys.
- **The Python suite will wipe the dev database** if run without `DATABASE_URL` pointed at a
  throwaway; `conftest.py` defaults to the dev database and runs `drop_all`.
- **`worker` and `beat` still run** but serve only the legacy backend; they are not on the
  active path.
- **Single instance, single region, no autoscaling, no read replicas.**

---

## 12. Repository state

- README states Java is the active backend; FastAPI is referenced only as legacy/rollback.
- `docs/rollback.md` documents the procedure, verification and known differences.
- Migration wording updated — no document still claims Python is routed.
- No debug files, cookie jars, credentials or stale migration TODOs.
- Secret scan clean; `.env` is gitignored; `OPENAI_API_KEY` in `.env.example` is empty.
- Benchmark artifacts retained under `perf/results/`, with `stress-*` runs labelled as
  rejected and never cited as results.
