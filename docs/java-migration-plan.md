# Java migration plan: FastAPI → Spring Boot

**Status: COMPLETE — Java is the active backend.** nginx routes `/api/v1/*` to
`api-java` and the Next.js BFF calls it directly. The Python service is retained as the
documented rollback target and behavioural reference ([rollback.md](rollback.md)).

Final numbers: **269 Java tests**, **234 Python tests**, **64/64 identical responses**
across seven suites (verified with and without an AI provider), **72/72 post-cutover
checks** against the Java-routed stack, and a measured bottleneck fixed for a 40% p95
improvement in read latency under login contention.

This plan treats the Python backend as the specification. It is built from an
inspection of the running application — the endpoint inventory came from the generated
OpenAPI schema, the schema from `information_schema` after `alembic upgrade head`, and
the JSON shapes from calling a live instance — not from reading source and guessing.

Companion documents:
- [api-compatibility.md](api-compatibility.md) — all 46 operations, request/response/status
- [java-schema-compatibility.md](java-schema-compatibility.md) — 12 tables, column by column

---

## 1. Existing architecture

A modular-by-domain FastAPI application. `backend/app/`:

```
config.py          pydantic-settings, env-driven
core/              database (async SQLAlchemy), security (Argon2 + PyJWT),
                   email (SMTP/console), exceptions, logging, middleware, redis
dependencies.py    get_db, get_current_user
api/v1/            health/ready, api_v1_router aggregation
modules/
  auth/            users, refresh tokens with rotation + reuse detection,
                   email verification, password reset
  workouts/        exercise library, workouts, workout_exercises
  nutrition/       meals, water entries, daily summary
  progress/        body measurements, goals
  profile/         one row per user
  training_insights/  deterministic analytics (owns no tables)
  ai/              OpenAI-backed generation, chat, insight explanation
```

Every module is `models.py` / `schemas.py` / `service.py` / `routes.py`. Routes never
touch the database — they call the service. `training_insights` is the deliberate
exception: no tables, and its service is split into `repository.py` (all DB access),
`analytics.py` (pure functions) and `rules.py` (thresholds as documentation).

Runtime characteristics that matter for the port:

- **Fully async.** asyncpg, `AsyncSession`, `AsyncOpenAI`. One request = one
  `AsyncSession`, committed by the `get_db` dependency on success and rolled back on
  any exception.
- **Ownership is enforced in the service layer**, per query: `if row.user_id != user.id:
  raise NOT_FOUND`. There is no row-level security and no shared filter.
- **Errors** flow through a single `AppException` type into a uniform envelope with a
  correlation ID.
- **Celery** runs exactly two tasks, both transactional email. **Celery Beat runs but
  has no schedule configured** — verified, `beat_schedule` does not appear anywhere in
  the repository. There are no scheduled jobs to migrate.
- **Redis** is used only as the Celery broker/result backend, plus a `PING` in the
  readiness probe. It is not a cache and holds no application state.

### Current data flow, end to end

```
Browser → nginx :80
            ├── /api/v1/*  → FastAPI (versioned; the real API)
            └── /api/*, /* → Next.js (BFF route handlers + pages)
                                └── BACKEND_INTERNAL_URL → FastAPI, server-side
```

The browser never holds a token. The Next.js BFF keeps the access token in an httpOnly
cookie `ff_access` (path `/`) and the refresh token in `ff_refresh` (path `/api`), and
performs silent refresh-and-retry on any 401.

---

## 2. Target Java architecture

A **modular monolith** — the same domain boundaries, same deployment shape, same
database. Spring Boot 3.5.x on Java 21.

### The one significant runtime change: async → blocking

FastAPI is async end to end. Spring Web (MVC) is a blocking, thread-per-request model,
and the brief asks for Spring Web rather than WebFlux — correctly, because WebFlux
would mean reactive data access and a rewrite of every service, which is a product
rewrite by another name.

Java 21 virtual threads are enabled:

```properties
spring.threads.virtual.enabled=true
```

**What that buys, precisely:** it removes the *platform-thread* ceiling on requests that
are parked waiting on IO, so a blocking call no longer pins one of a fixed pool of ~200
OS threads. That is the only thing it does.

**What it does not do — and must not be relied on:**

- It is **not a timeout.** A virtual thread waiting forever on an unresponsive provider
  waits forever, just more cheaply. Every outbound call keeps an explicit timeout
  (§ *AI failure isolation* below).
- It is **not a connection limit.** HikariCP remains a hard, bounded resource. Cheap
  threads make it *easier* to exhaust the pool, because more requests can arrive at the
  connection-acquisition point simultaneously. The pool size and the connection-timeout
  are the real limits on database concurrency and are tuned and measured, not assumed.
- It is **not failure isolation.** Unbounded cheap concurrency against a degraded
  dependency is a way to amplify an outage, not contain it. Bounding concurrent AI calls
  and failing fast when that bound is reached is a separate, explicit mechanism.
- It is **not a performance claim.** Whether it helps this workload is a question for
  Phase 12's measurements.

Two known sharp edges to handle rather than discover: pinning on `synchronized` blocks
holding a carrier thread, and the fact that `ThreadLocal`-based context (MDC correlation
ids) needs checking under virtual threads.

### AI failure isolation

The Python backend already bounds its OpenAI calls — `openai_timeout_seconds` defaults to
20s explicitly, because the SDK's own default is 600s and "one hung upstream request ties
up a worker for ten minutes". That reasoning carries over unchanged, and the timeout
carries over with it.

Rules the Java implementation holds to:

1. **Explicit timeouts on every outbound AI call**, connect and read, configured from the
   same `OPENAI_TIMEOUT_SECONDS` setting. Never the client library's default.
2. **No database transaction is held open across an AI call.** Read what the prompt needs
   and commit; call the provider outside any transaction; open a new transaction if the
   result must be persisted. A 20-second provider call inside a transaction holds a
   Hikari connection and any row locks for 20 seconds. This is why
   `training_insights.service` computes the whole insight and commits *before* the
   `explain` flag is consulted — a structure the port preserves.
3. **Bounded concurrent AI calls**, so a degraded provider cannot occupy unbounded
   resources. Over the limit, fail fast with the existing 503 rather than queue.
4. **Failure degrades only AI features.** Workouts, nutrition, progress and the
   deterministic analytics never call a provider on their normal paths, and the contract
   tests assert that training insights still return 200 when AI is unavailable.

### Package structure

```
backend-java/src/main/java/com/fitnesstracker/
├── FitnessTrackerApplication.java
├── config/            JacksonConfig, JpaConfig, RedisConfig, OpenApiConfig, AsyncConfig
├── security/          SecurityConfig, JwtService, JwtAuthenticationFilter,
│                      CurrentUser resolver, entry point + access denied handlers
├── common/
│   ├── api/           ApiResponse<T>, ErrorResponse, ErrorDetail, ErrorCode
│   ├── exception/     AppException, GlobalExceptionHandler
│   └── validation/    password constraint, shared annotations
├── auth/              controller/ service/ repository/ entity/ dto/
├── workouts/          controller/ service/ repository/ entity/ dto/
├── nutrition/         "
├── progress/          "
├── profile/           "
├── traininginsights/
│   ├── controller/ service/ repository/ dto/
│   └── domain/        TrainingAnalytics, AnalysisRules — pure, no Spring
├── ai/
│   ├── AiProvider.java          (interface)
│   ├── OpenAiProvider.java      (RestClient)
│   ├── FakeAiProvider.java      (tests / no key)
│   └── controller/ service/ dto/
└── jobs/              Redis Streams producer, consumer, email handlers
```

Domain-first, mirroring the Python layout one-to-one so a reviewer can diff the two
side by side. No global `controller/` `service/` `repository/` folders.

`traininginsights/domain/` holds no Spring annotations at all — it is the pure analytics
engine, the direct counterpart of `analytics.py`, and it is unit-testable without a
context.

---

## 3. Compatibility matrix

| Python component | Java replacement | Notes |
|---|---|---|
| FastAPI routes | Spring MVC `@RestController` | paths, methods and status codes preserved exactly |
| Pydantic schemas | Java `record` DTOs + Jakarta Validation | `record` for requests and responses; entities never leave the service layer |
| SQLAlchemy async ORM | Spring Data JPA / Hibernate 6 | `ddl-auto=validate`, `open-in-view=false` |
| Alembic | Flyway (baseline strategy, §5) | Alembic history is **not** rewritten |
| `Depends(get_current_user)` | Spring Security filter + `@AuthenticationPrincipal` | resolves the same `User` |
| `core/security.py` (PyJWT HS256) | `NimbusJwtEncoder` / `NimbusJwtDecoder` via `spring-security-oauth2-jose` | same secret, same HS256, same claims |
| Native PostgreSQL enum columns | `PgEnumUserType` + `@Column(columnDefinition = ...)` | **not** `SqlTypes.NAMED_ENUM` — measured and rejected in Phase 0.5, see §8.5 |
| `argon2-cffi` PasswordHasher | `Argon2PasswordEncoder` | **must be parameter-matched** — see R2 |
| Celery + Redis broker | Redis Streams + consumer group (§6) | same Redis, no new infrastructure |
| Celery Beat | *nothing* | it currently schedules no jobs |
| `redis.asyncio` | Spring Data Redis (`StringRedisTemplate`) | |
| pytest | JUnit 5 | |
| pytest + real PostgreSQL fixture | Testcontainers PostgreSQL | matches the existing "no H2, use real Postgres" stance |
| `AsyncOpenAI` | `AiProvider` interface + `RestClient` implementation | no vendor SDK dependency |
| `/api/v1/health`, `/ready` | **explicit controllers**, not Actuator | see deviation below |
| `CorrelationIdMiddleware` | `OncePerRequestFilter` + MDC | same `X-Correlation-ID` header |
| `smtplib` | `spring-boot-starter-mail` | Mailhog in dev, unchanged |

### Deviations from the suggested mapping, and why

**Health endpoints are not replaced by Actuator.** The brief maps health endpoints to
Actuator. Actuator's `/actuator/health` has a different path *and* a different body
shape, and the current `/api/v1/ready` returns a specific envelope with a `checks` map
and HTTP 503 on degradation. The Docker healthcheck, nginx and the frontend all depend
on it. So: keep `/api/v1/health` and `/api/v1/ready` as plain controllers that preserve
the contract, and mount Actuator **additionally** at `/actuator/**` for metrics and
Prometheus scraping. Actuator is a gain, not a substitute.

**No official OpenAI SDK.** The Python code makes ordinary `chat/completions` calls. A
`RestClient` behind the `AiProvider` interface is fewer dependencies, keeps the vendor
boundary explicit as the brief requires, and — usefully — respects `OPENAI_BASE_URL`,
which is what lets a stub server stand in during end-to-end tests.

**No Lombok.** Java 21 `record` covers every DTO, and constructor injection is required
anyway. Lombok's remaining value here is `@Slf4j` and entity accessors, which does not
justify an annotation processor in the build or the "excessive magic" the brief warns
against. If entity boilerplate becomes genuinely painful, revisit with a concrete case.

**Maven, not Gradle.** The repository's existing build config is declarative and
trivial (`pyproject.toml`, `package.json`); there is no custom build logic, no
multi-module layout and no build-performance problem to solve. Maven's POM matches that
character, `./mvnw` makes CI reproducible without a local install, and the Spring Boot,
Flyway and Testcontainers plugins are all first-class. Gradle would be the answer if we
needed custom tasks or a large multi-module graph. We do not.

---

## 4. Proposed Maven dependencies

Parent: `spring-boot-starter-parent` **3.5.3** (latest on Maven Central at inspection;
re-confirm when Phase 1 starts). `<java.version>21</java.version>`.

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | Spring MVC |
| `spring-boot-starter-data-jpa` | Hibernate 6 |
| `spring-boot-starter-security` | filter chain, `Argon2PasswordEncoder` |
| `spring-security-oauth2-jose` | Nimbus HS256 encode/decode |
| `spring-boot-starter-validation` | Jakarta Bean Validation |
| `spring-boot-starter-data-redis` | Redis Streams + readiness ping |
| `spring-boot-starter-actuator` | health, metrics |
| `spring-boot-starter-mail` | SMTP, replacing `smtplib` |
| `micrometer-registry-prometheus` | `/actuator/prometheus` |
| `flyway-core`, `flyway-database-postgresql` | migrations |
| `postgresql` (runtime) | JDBC driver |
| `bouncycastle` (`bcprov-jdk18on`) | required by `Argon2PasswordEncoder` |
| `springdoc-openapi-starter-webmvc-ui` | `/api/docs` parity in non-prod |
| `spring-boot-starter-test` | JUnit 5, AssertJ, MockMvc |
| `spring-security-test` | authenticated MockMvc requests |
| `spring-boot-testcontainers`, `testcontainers:postgresql`, `testcontainers:junit-jupiter` | integration tests |

No Kafka, no RabbitMQ, no Kubernetes, no second LLM vendor, no vector database.

---

## 5. Database and Flyway strategy

**The Java backend attaches to the existing schema. It does not create a new one.**

`spring.jpa.hibernate.ddl-auto=validate` — never `update`, `create` or `create-drop`,
in any profile, including tests. A drift between entity and schema must fail the build,
not silently rewrite a column.

### Flyway: baseline, not replay

Rewriting the eight Alembic migrations as Flyway migrations would be pointless work and
actively dangerous — replaying them against a populated database would attempt to
recreate live tables.

The strategy is:

1. **`V1__baseline_schema.sql`** — the complete schema at Alembic `008`, generated with
   `pg_dump --schema-only` plus the 39 seeded `exercises` rows. This is what builds a
   *fresh* database: local dev, CI, and every Testcontainers instance. It means
   integration tests run against a byte-identical replica of production's schema.
2. **The existing deployed database is baselined, never migrated.**
   `spring.flyway.baseline-on-migrate=true`, `baseline-version=1`. On first Java start
   against the live database Flyway records V1 as already applied and executes nothing.
3. **`alembic_version` is left in place.** It is the record of where Python stopped and
   the rollback anchor. It is dropped only in a later cleanup, after Python is retired.
4. **All schema changes after the cutover are `V2__*.sql` onward.** There is no
   double-headed migration story, because Python stops changing the schema the moment
   this migration begins.

Non-negotiable: no Flyway migration may drop or recreate an existing table.

Verification, added to CI in Phase 1:
- `V1` applied to an empty container must produce a schema identical to
  `alembic upgrade head` on an empty container. The two dumps are diffed.
- `baseline-on-migrate` against an Alembic-built database must apply zero migrations.

---

## 6. Background jobs

### What actually exists

Two Celery tasks, both fire-and-forget transactional email:
`auth.send_verification_email`, `auth.send_password_reset_email`.
No scheduled jobs. The `beat` container schedules nothing.

Also worth noting: the Python enqueue is already best-effort. `_enqueue()` catches every
exception from `.delay()` and logs, specifically so a Redis outage cannot fail
registration. Email is explicitly the non-essential half of those flows.

### Choice: Redis Streams with a consumer group

Justification, not resume-driven: Redis is already deployed and already the Celery
broker, so this adds **zero new infrastructure**. It preserves the property the current
system actually has — at-least-once delivery once accepted, surviving a worker crash —
which a plain `@Async` executor would lose. Kafka or RabbitMQ would be new
infrastructure for two email tasks; the brief rules that out and it would be wrong
anyway.

Design:

| Concern | Mechanism |
|---|---|
| enqueue | `XADD email:stream * type=... payload=...`, wrapped in the same catch-and-log so a Redis outage cannot fail registration |
| consume | `XREADGROUP` on group `email-workers`, one consumer per instance |
| claim | `XAUTOCLAIM` on a fixed interval reclaims entries pending longer than a threshold — this is the crashed-worker recovery path |
| ack | `XACK` only after the SMTP send returns |
| retry | bounded attempts, tracked on the message; exponential backoff between claims |
| failure | after max attempts, `XADD` to `email:dead` and `XACK` the original, so a poison message cannot loop forever |
| idempotency | keyed on the token hash — re-sending a verification email for an already-consumed token is a no-op, so at-least-once is safe here |
| shutdown | `@PreDestroy` stops the read loop, finishes in-flight sends, leaves unacked entries for `XAUTOCLAIM` |
| trimming | `XTRIM MAXLEN ~` bounds stream growth |

Each of these gets an integration test against a Redis container, including the
crash-recovery path (deliberately failing to ack, then asserting `XAUTOCLAIM` redelivers).

### Scheduling

Nothing to migrate. Spring `@Scheduled` is introduced only when a real job exists.

One candidate is already visible in the schema: the partial indexes
`ix_refresh_tokens_expires (expires_at) WHERE revoked_at IS NULL` and the equivalents on
both token tables are exactly the indexes an expired-token cleanup job would need, and
no such job was ever written. That is a genuine improvement — but it is **new
functionality**, so it belongs after parity, not inside the migration.

---

## 7. Migration sequence

Each phase ends with: compiles, tests pass, contract tests green, documentation
updated. No phase begins while the previous one has known failures.

| Phase | Scope | Exit criterion |
|---|---|---|
| **0.5 — Risk spike** | R1/R2/R3 validated against Testcontainers | **done** — 25 tests passing, see §8.5 |
| **1 — Foundation** | Maven project, config, datasource, Flyway baseline, Redis, Jackson, exception handler, correlation filter, health/ready, Dockerfile, Compose, Testcontainers base, Actuator | **done** — see §8.6 |
| **2 — Auth** | users + 3 token tables, register/login/refresh/logout/verify/reset/me, Spring Security chain, Celery→Redis Streams email, Actuator isolation | **done** — see §8.7 |
| **3 — Workouts** | Exercise, Workout, WorkoutExercise, library, CRUD, ownership, date filters | **done** — see §8.8 | contract tests for all 7 operations; summary-vs-detail shapes preserved |
| **4 — Nutrition** | meals, water entries, daily summary | **done** — see §8.8 | aggregate returns zeros on an empty day |
| **5 — Progress** | measurements (upsert), goals | **done** — see §8.8 | same-day upsert returns 201 and updates in place |
| **6 — Profile** | single-row upsert, `has_profile` | **done** — see §8.8 | omitted fields null out |
| **7 — Training insights** | pure analytics engine + repository + 4 endpoints | **shared fixture parity** (§8) — every case produces the same classification |
| **8 — AI** | `AiProvider`, OpenAI impl, fake impl, 4 endpoints, insight explanation | 502/503 mapping matches; insights still 200 when AI fails |
| **9 — Background jobs** | Redis Streams producer/consumer, email handlers | crash-recovery redelivery test passes |
| **10 — Cutover** | Compose runs both backends; nginx switched to Java; full end-to-end | every success criterion met |
| **11 — Retire Python** | only after 10 holds | — |
| **12 — Performance** | k6 workloads, real measurement | numbers that were actually measured |

Phases 3–6 are largely mechanical and low-risk. Phases 2, 7 and 8 carry the real risk.

---

## 8. Contract testing

The most important validation in this migration, because "it compiles and the tests
pass" says nothing about whether the frontend still works.

### Golden HTTP fixtures

A fixture set of request/response pairs captured from the **Python** implementation,
stored in `contract/`, replayed against both backends. Comparison normalises the
non-deterministic fields — UUIDs, timestamps, correlation IDs, JWTs — and asserts
equality on everything else: status code, field names, types, enum values, null-vs-absent.

Capturing has already been prototyped during this inspection: the OpenAPI schema, the
schema dump and the sample responses in this document were produced exactly this way.

### Shared analytics fixtures — the strongest test available

The deterministic engine is the highest-value and highest-risk port, and it is perfectly
suited to language-neutral fixtures because it is pure: a list of sessions in, a
classification out.

The plan is to extract the 53 existing Python unit-test cases into
`contract/training-insights-fixtures.json`:

```json
{
  "name": "four_flat_sessions_over_enough_days",
  "category": "strength",
  "sessions": [
    {"performed_at": "2026-06-01", "sets": 3, "reps": 8, "weight_kg": 45.0},
    ...
  ],
  "expect": {
    "classification": "possible_plateau",
    "metric_basis": "load",
    "sessions_analyzed": 4,
    "plateau_detected": true,
    "suggestion_kind": "review_exercise"
  }
}
```

Both the Python suite and a JUnit `@ParameterizedTest` read the same file. A divergence
in threshold handling, ordering, or float comparison fails in both languages against one
source of truth. This also protects the engine from drifting after Python is retired.

### Auth behaviour tests

Scripted sequences rather than single calls, run against both backends:
login → refresh → refresh with the *old* token → assert 401 **and** assert every sibling
token is now revoked.

---

## 8.5 Phase 0.5 — risk spike results

Run before any domain migration, against Testcontainers PostgreSQL 16. **25 tests, all
passing** (`./mvnw verify`). Code lives in `backend-java/src/test/java/com/fitnesstracker/spike/`.

| Risk | Verdict | Outcome |
|---|---|---|
| R1 refresh-token reuse durability | **Resolved** | 5 tests; revocation proven committed via direct SQL |
| R2 Argon2 compatibility | **Resolved** | 12 tests; verified in both directions against real Python hashes |
| R3 native PostgreSQL enums | **Resolved — proposed mapping was wrong** | 8 tests; design changed |

### R3 — the proposed enum mapping does not work

The plan proposed `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`. Measured result: **it fails**, in
the worst possible way.

`ddl-auto=validate` **passes**, because the schema validator only compares column types
and never exercises a bind. Every insert then fails at runtime:

```
ERROR: invalid input value for enum user_role: "ADMIN"
InvalidDataAccessApiUsage: No enum constant UserRole.admin
```

NAMED_ENUM binds `Enum.name()` — uppercase by Java convention — while this schema's
labels are lowercase (`user`, `strength`). Startup is silent; the failure appears on the
first write. Had this been assumed rather than measured, it would have shipped.

Two further attempts were measured and also rejected:

| Attempt | Result |
|---|---|
| `AttributeConverter` → `String` + `@JdbcTypeCode(SqlTypes.OTHER)` | `MappingException: no type mapping for SqlTypes code 1111 (OTHER)` — context fails to start |
| Custom `UserType` alone | Same `MappingException`: Hibernate still needs a SQL *type name* for the column |

**The design change.** A custom `UserType` that owns both directions, *plus* an explicit
`columnDefinition` naming the PostgreSQL type:

```java
public abstract class PgEnumUserType<E extends Enum<E> & PgEnum> implements UserType<E> {
    @Override public int getSqlType() { return Types.OTHER; }
    @Override public void nullSafeSet(PreparedStatement st, E value, int i, ...) {
        st.setObject(i, value.getValue(), Types.OTHER);   // untyped; PostgreSQL coerces
    }
    @Override public E nullSafeGet(ResultSet rs, int position, ...) {
        String label = rs.getString(position);
        return label == null ? null : fromValue.apply(label);
    }
}
```

```java
@Type(UserRoleType.class)
@Column(name = "role", nullable = false, columnDefinition = "user_role")
private UserRole role = UserRole.USER;
```

`Types.OTHER` makes the driver send the label untyped so PostgreSQL resolves it against
the target column's enum type — which is also what makes the value usable as a *query
parameter*, since PostgreSQL will not compare `user_role = character varying`.
`columnDefinition` supplies the type name Hibernate needs for code 1111.

Java constants stay uppercase; the lowercase label lives on a `PgEnum.getValue()` method
that will also serve as Jackson's `@JsonValue`. One shared base class covers all five
enum types; each needs a three-line subclass.

Verified by 8 tests: validation passes, the columns really are `USER-DEFINED`, insert
writes `admin` (asserted by reading `role::text` in raw SQL, not through Hibernate), read
maps back, **a row written outside Hibernate reads correctly** (the side-by-side case
where Python wrote the row), derived queries and JPQL both filter on the enum, and
updates persist.

**Consequence for the plan:** the compatibility matrix row for enums changes from
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` to `PgEnumUserType + columnDefinition`, and
[java-schema-compatibility.md](java-schema-compatibility.md) is updated to match.

### R1 — reuse detection survives the rollback

The hazard: the 401 that reports the reuse rolls back the transaction that performed the
revocation. Detection works, the security action silently does not.

Resolved with `Propagation.REQUIRES_NEW` on a *separate bean* — Spring's proxy-based
`@Transactional` is a no-op on self-invocation, so an inlined private method would have
reintroduced the bug while looking correct.

What makes the test trustworthy: **every revocation assertion reads through `JdbcTemplate`,
not the repository.** Reading back through JPA inside the same transaction would hit the
first-level cache and pass against an implementation that revokes nothing.

Five tests: siblings revoked and committed; no replacement token left behind (the outer
transaction *does* still roll back); normal rotation revokes only the presented token;
revocation does not cross users; and — the one that earns its place — **a test asserting
the naive implementation loses the revocation**. `rotateNaively` is kept in the codebase
unreachable from any controller, purely so that anyone who "simplifies"
`TokenRevocationService` by inlining it gets a red test instead of a silent security
downgrade.

### R2 — Argon2 compatibility, both directions

`contract/argon2-fixtures.json` holds four hashes produced by the real Python
implementation (typical password, minimum-length, non-ASCII, 128-char). The Java tests
read that file directly rather than a copy, so the two cannot drift.

Confirmed parameters: **argon2id, v=19, m=65536 KiB, t=3, p=4**, 32-byte hash, 16-byte
salt.

Twelve tests: Java verifies every Python hash; rejects wrong passwords; the configured
encoder writes *identical cost parameters* (not merely verifiable ones); and Spring's own
`defaultsForSpringSecurity_v5_8()` is asserted to be **weaker** (m=16384, p=1) — so that
if a future Spring release changes its defaults, the test fails and prompts a review
rather than silently changing behaviour.

The reverse direction was checked outside JUnit: the Java test writes its hashes to
`target/java-argon2-hashes.txt` and the Python backend's own `verify_password` verifies
all four. Both directions matter because during the side-by-side period each backend
writes hashes the other must read.

### Environment findings worth recording

Two things cost time and will cost it again on another machine or in CI:

1. **Testcontainers could not reach Docker Desktop.** `/info` returned HTTP 400 with an
   empty body. Cause: docker-java defaults to Docker API **v1.32**, and Engine 29.x sets
   `MinAPIVersion` to **1.40**. Confirmed by probing the socket directly — `/v1.32/info`
   → 400, `/v1.40/info` → 200. Workaround in use: `-Dapi.version=1.44`. Phase 1 should
   pin this in the surefire configuration so it is not a per-developer incantation.
2. On macOS, Docker Desktop's socket is `~/.docker/run/docker.sock`, not
   `/var/run/docker.sock`; `~/.testcontainers.properties` needs `docker.host` set.

Neither affects production. Both belong in the backend-java README.

## 8.6 Phase 1 — foundation

### What runs

```
backend-java/src/main/java/com/fitnesstracker/
├── common/
│   ├── api/          ApiResponse, ErrorResponse, ErrorCode — the wire envelopes
│   ├── exception/    AppException, GlobalExceptionHandler
│   ├── persistence/  PgEnumUserType — native PostgreSQL enum binding (R3)
│   └── web/          CorrelationIdFilter — X-Correlation-ID + MDC
├── config/           JacksonConfig, PasswordEncoderConfig, ClockConfig
├── health/           /api/v1/health, /api/v1/ready
├── auth/             User, RefreshToken, token rotation (partial — Phase 2)
└── workouts/         Exercise entity only (partial — Phase 3)
```

### Flyway

`V1__baseline_schema.sql` was generated with `pg_dump --schema-only` from a database
built by `alembic upgrade head`, plus the 39 seeded `exercises` rows. It is not
hand-written and the eight Alembic migrations are not replayed.

`SchemaParityTest` introspects the Flyway-built container and compares it, line for line,
with `contract/expected-schema.txt` — the same introspection taken from the Alembic-built
database. Each backend's own migration-history table (`alembic_version`,
`flyway_schema_history`) is excluded, since it records how its owner tracks migrations
rather than what the application schema is.

### Error handling and JSON

`GlobalExceptionHandler` renders the envelope captured in
[api-compatibility.md](api-compatibility.md). Two behaviours differ deliberately from
Spring's defaults: validation failures return **400**, not 422; and an unhandled exception
returns a fixed message plus the correlation id, with the stack trace logged and nothing
about the internals returned. A test asserts a thrown message containing a password and a
connection string leaks neither, nor an exception class name.

`JacksonConfig` pins snake_case, ISO-8601 timestamps and null-writing — the three things
the frontend depends on.

### Observability

`/api/v1/health` and `/api/v1/ready` remain the compatibility endpoints, as plain
controllers. Actuator is mounted additionally at `/actuator/**` with only
`health,info,prometheus,metrics` exposed; a test asserts `env`, `beans`, `configprops`,
`heapdump`, `threaddump`, `loggers` and `mappings` all return 404, because Actuator shares
the application port.

### Docker

`backend-java/Dockerfile` is multi-stage: `eclipse-temurin:21-jdk-jammy` to build,
`21-jre-jammy` to run, non-root `app` user (uid 1001), `MaxRAMPercentage=75` so the JVM
sizes its heap from the cgroup limit, and a `HEALTHCHECK` on `/api/v1/health`.

Tests are skipped inside the image build on purpose: they use Testcontainers, which needs
a Docker daemon that a build container does not have. `./mvnw verify` runs on the host, in
CI, before the image is built.

The first version of this file used `dependency:go-offline` for a cached dependency layer.
It failed — that goal resolves far more than the build needs, including artifacts that do
not exist. Replaced with a BuildKit cache mount on `/root/.m2`, which is both faster and
does not pretend dependency resolution is a separate build concern.

### Compose: side-by-side, not a cutover

`docker-compose.yml` gains an `api-java` service. **`api` (Python) is unchanged and is
still the only service nginx routes to.**

| | `api` (Python) | `api-java` (Spring Boot) |
|---|---|---|
| Host port | not published | **8001**, for parity testing only |
| nginx `/api/v1/` | **yes** | no |
| Database | `db` | **the same `db`** |
| Redis | `redis` | **the same `redis`** |
| `SECRET_KEY` | from `.env` | **the same value** |

Sharing `SECRET_KEY` is what will make the cutover gradual: a JWT minted by either
backend validates against the other, so flipping the nginx `upstream` will not log anyone
out. Sharing the database is what makes parity testable against real data.

`DATABASE_URL` differs in form, not in target — the Python service uses
`postgresql+asyncpg://…` and the Java service the JDBC `jdbc:postgresql://…`. Both are
forced to the Compose DNS name `db` in the `environment:` block so a developer's
localhost `.env` cannot point a container at the host.

### The Prometheus 404: root cause

`/actuator/prometheus` returned 404 under test while the dependency was present and the
endpoint was in the exposure list. The condition-evaluation report gave the answer:

```
PrometheusMetricsExportAutoConfiguration:
   Did not match:
      - @ConditionalOnEnabledMetricsExport management.defaults.metrics.export.enabled
        is considered false (OnMetricsExportEnabledCondition)
   Matched:
      - @ConditionalOnClass found 'io.micrometer.prometheusmetrics.PrometheusMeterRegistry'
```

**Spring Boot's test support disables metrics export by default.** `@SpringBootTest`
applies `DisableObservabilityContextCustomizer`, which sets
`management.defaults.metrics.export.enabled=false` so that tests do not ship metrics to
real backends. The application configuration was correct the whole time; the test
environment was suppressing it.

Fix: `@AutoConfigureObservability` on `ActuatorEndpointsTest`, which restores production
behaviour for that context. The test now asserts HTTP 200, the exposition format
(`# HELP` / `# TYPE` lines), the specific series Phase 12 will benchmark
(`jvm_memory_used_bytes`, `jvm_threads_live_threads`, `hikaricp_connections`,
`process_uptime_seconds`), and the `application="fitness-tracker-backend"` common tag.

This was worth chasing rather than working around: an assertion that 404 was expected
would have hidden a working feature behind a permanently wrong test.

### Deliberately insecure code removed from production

The Phase 0.5 spike put a `rotateNaively` method in `TokenRotationService` to demonstrate
that revoking inside the caller's transaction loses the revocation. Unreachable or not,
shipping deliberately insecure code in the production artifact is not worth the
convenience.

It now lives in `backend-java/src/test/java/.../support/NaiveTokenRotation.java`, on the
test classpath only. The regression test is unchanged in substance: it still proves the
naive approach leaves sibling tokens active while the correct one does not, so anyone who
inlines `TokenRevocationService` still gets a red build.

### Phase 1 validation results

`./mvnw verify` — **45 tests, 0 failures** (Testcontainers PostgreSQL 16 + Redis 7):

| Suite | Tests |
|---|---|
| `Argon2CompatibilitySpikeTest` | 12 |
| `EnumMappingSpikeTest` | 8 |
| `ErrorEnvelopeTest` | 7 |
| `HealthContractTest` | 5 |
| `ActuatorEndpointsTest` | 5 |
| `RefreshTokenReuseTransactionSpikeTest` | 5 |
| `SchemaParityTest` | 3 |

Docker validation — **26 checks, 0 failures**, against the running Compose stack:

| Group | Result |
|---|---|
| Java `/api/v1/health`, `/api/v1/ready` (200, database up, redis up) | 5/5 |
| Java Actuator: health, metrics, prometheus 200 + format + series + tag, `env` closed | 8/8 |
| Flyway startup and Hibernate validation | 2/2 |
| Schema state in the shared database | 5/5 |
| Python backend still serving through nginx | 4/4 |
| Frontend still served by nginx | 2/2 |

**Flyway against the existing (Alembic-built) database** — `flyway_schema_history` holds a
single row, `version 1, type BASELINE, success t`. V1 was **not** executed against live
data, and `alembic_version` still reads `008_exercise_scope_indexes`.

**Flyway against a genuinely empty database** — verified separately by creating
`fitforge_fresh` and running the image against it: *"All configured schemas are empty;
baseline operation skipped"* → *"Migrating schema public to version 1 - baseline schema"*
→ *"Successfully applied 1 migration"*, then `Started FitnessTrackerApplication`, which
means `ddl-auto=validate` accepted the result. The database then contained 13 tables (12
application + `flyway_schema_history`, no `alembic_version`), 39 seeded exercises, 5 enum
types and 37 indexes.

**Python backend unaffected** — `pytest` 142 passed, `ruff check` clean, with no source
changes from this phase. `nginx/` and `frontend/` were not touched.

### Remaining Phase 1 limitations

- **Actuator is unauthenticated on the application port** and published on host 8001.
  Acceptable while `api-java` is parity-test-only and unrouted; before it takes production
  traffic it needs a separate management port or authentication.
- **CI does not build or test `backend-java` yet.** The Python and frontend jobs are
  untouched. Adding the Java job is the first Phase 2 chore.
- **Cold image builds are slow** (Maven repository starts empty). The BuildKit cache mount
  makes rebuilds fast; CI will want a cached `~/.m2`.
- **`docker-compose.prod.yml` does not mention `api-java`.** Deliberate — it is not a
  production service yet.

## 8.7 Phase 2 — authentication

### JWT compatibility, documented exactly

Sharing `SECRET_KEY` is necessary but nowhere near sufficient — the claim set, the
`type` claim, the date encoding and the *absence* of `iss`/`aud` all have to agree.

| | Access | Refresh |
|---|---|---|
| Algorithm | HS256 over the raw UTF-8 bytes of `SECRET_KEY` | same |
| `sub` | user id (UUID string) | user id |
| `jti` | random UUID | random UUID — **also the `refresh_tokens` primary key** |
| `type` | `"access"` | `"refresh"` |
| `iat` / `exp` | NumericDate (seconds) | same |
| `role` | yes | — |
| `email_verified` | yes | — |
| `iss` / `aud` | **none** | **none** |
| TTL | 900 s (15 min) | 604 800 s (7 days) |

Adding an issuer or audience would produce tokens the Python backend rejects, so neither
is set.

**One deliberate divergence.** RFC 7518 §3.2 requires an HMAC key at least as long as the
hash output; Nimbus enforces it, PyJWT only warns. A `SECRET_KEY` between Python's
16-character minimum and 32 bytes is therefore accepted by Python and refused by Java.
Rather than weaken the check or hash the secret (which would break compatibility), the
Java service validates the length at startup with an actionable message. Deployments must
use a secret of at least 32 bytes — which they should anyway.

### Proof, in both directions

`contract/jwt-fixtures.json` is generated by `contract/generate_jwt_fixtures.py` using
PyJWT and the real Python token code. `JwtCompatibilityTest` drives those tokens through
the actual `/api/v1/auth/me` and `/api/v1/auth/refresh` endpoints — 15 tests covering two
accepted access tokens, a refresh token, and nine negative cases: expired, wrong signing
key, refresh-used-as-access, access-used-as-refresh, missing `type`, non-UUID `sub`,
structurally malformed, tampered payload, expired refresh.

`contract/verify_java_jwts.py` closes the loop: the Java suite writes the tokens it mints,
and the Python backend's own `decode_token` verifies all 15 properties including both
type-confusion rejections.

The acceptance fixtures are minted with a ten-year TTL on purpose. A fixture carrying the
real 15-minute lifetime stops being a valid token an hour later, and a compatibility test
that fails with the passage of time is worse than no test. The production TTLs are
asserted separately from a `ttl_reference` token by inspecting `exp - iat`.

Live, in Docker, both backends sharing one database and one secret:

- register on Python → log in on Java
- register on Java → log in on Python (proving Argon2 both ways at runtime)
- Python access token → Java `/me`, and the reverse
- Python refresh token → Java `/refresh`, and the reverse
- a token rotated by Python, replayed on Java → 401 **and** every sibling session revoked
  in the shared table

### Refresh rotation

The proven Phase 0.5 design is intact: `TokenRevocationService` is a separate bean with
`REQUIRES_NEW`, because the 401 that reports reuse rolls back the transaction that
performed the revocation. `RefreshTokenReuseTransactionSpikeTest` was rewritten to drive
the real rotation path with real tokens, and still asserts revocation with `JdbcTemplate`
rather than through JPA — a read through the persistence context would pass against an
implementation that revokes nothing.

The naive implementation now lives only in test code (`support/NaiveTokenRotation`).

### Transactional email: Celery → Redis Streams

Only two things ever needed migrating: the verification and password-reset emails. Celery
Beat scheduled nothing, so nothing scheduled was migrated and no scheduler was introduced.

| Concern | Mechanism |
|---|---|
| enqueue | `XADD`, **best-effort** — wrapped in catch-and-log so a Redis outage degrades to "no email", never to "registration failed", exactly as the Python `_enqueue` does |
| consume | `XREADGROUP` on group `email-workers`, one named consumer per process |
| acknowledge | `XACK` only after the send returns |
| recover | `XAUTOCLAIM` reclaims entries pending beyond `app.email.claim-after` — the crashed-worker path |
| retry | attempt counter on the message, re-added on failure |
| give up | past `app.email.max-attempts` the message moves to `fitness:email:dead` and is acked, so one poison message cannot loop |
| idempotency | at-least-once is safe: a duplicate email carries the same one-time token, which is single-use at the database level |
| shutdown | the loop stops accepting work; anything unacked is recovered on next start |
| observability | `email.enqueued`, `email.sent`, `email.failed`, `email.dead_lettered`, `email.reclaimed`, `email.send.duration` |

Six tests against a real Redis container cover delivery, retry, dead-lettering,
crash-recovery, that enqueue never throws when Redis is unreachable, and that no token
material reaches a log line.

Stream names are configurable (`app.email.stream`) because one Redis container is shared
by every test context, and without per-context streams the contexts consume one another's
messages.

### Actuator security

Actuator now listens on its own port (`management.server.port`, default 9000), which
Docker Compose deliberately does **not** publish. It is reachable from inside the Compose
network — where Prometheus will scrape it — and from nowhere else. That network isolation
is the access control; a shared credential in a scrape config would add nothing over it.

`/api/v1/health` and `/api/v1/ready` stay on the application port, unauthenticated,
because nginx, the Docker healthcheck and the frontend all use them.

Verified in Docker: `/actuator/prometheus` on the published port 8001 → 401; on host port
9000 → unreachable; from inside the network → 200 with `jvm_memory_used_bytes` and the
`application="fitness-tracker-backend"` tag.

### Phase 2 results

| Suite | Result |
|---|---|
| `./mvnw verify` | **86 tests, 0 failures** |
| `contract/verify_java_jwts.py` | 15/15 |
| Docker cross-backend auth | 25/25 |
| Next.js BFF against Java | 21/21 |
| Python regression (`pytest`) | 142 passed, unchanged |

The BFF check ran the real session flow through nginx with `BACKEND_INTERNAL_URL` pointed
at `api-java`: register, login, httpOnly cookie issuance, `/me`, all seven protected pages
rendering, silent refresh rotating the cookie, and logout invalidating the session — plus
an assertion that no token ever reaches the browser. Routing was restored to Python
afterwards; **nginx still sends all `/api/v1/` traffic to the Python backend.**

### Known limitations after Phase 2

- Only auth is migrated. Workouts, nutrition, progress, profile, training insights and AI
  remain Python-only, and `/api/v1/**` on the Java service returns 401 for them because
  nothing is mapped.
- `Profile` is mapped with only its identity columns, enough for `has_profile`. The full
  entity arrives in Phase 6.
- `clientIp` uses the direct peer address, matching Starlette's `request.client.host`.
  Behind nginx that is the proxy, not the user — true of the Python backend too, so the
  stored value stays consistent. Reading `X-Forwarded-For` would be a behaviour change.

## 8.8 Phases 3–6 — workouts, nutrition, progress, profile

Four CRUD domains, migrated the same way and validated the same way.

### The check that matters: identical responses

`contract/compare_backends.py` sends identical requests to both backends and diffs the
JSON field by field. Volatile values (ids, timestamps, tokens, correlation ids, and the
per-backend test address) are normalised to a type marker; everything else must match
exactly.

**38 request/response pairs, 0 differences** across all four domains — create, read,
update, delete, list shapes, date filtering, upserts, enum values, null-vs-omitted, and
the error cases.

This is what catches what unit tests cannot: a renamed field, a changed status code, a
list entry with a different shape, an omitted key where Python emits `null`.

### Behaviours preserved that a rewrite would have "improved"

| Behaviour | Why it stays |
|---|---|
| `POST /exercises` returns **201 even when the row already existed** | get-or-create; the exercise picker depends on it |
| `POST /measurements` **upserts** and still returns 201 | `UNIQUE(user_id, recorded_at)` — one check-in per day |
| `PUT` is a **full replace** everywhere — omitted fields become `NULL` | it is a PUT, not a PATCH; the forms send the whole object |
| `GET /profile` is **404** before the first save | the frontend branches on it, along with `has_profile` |
| Another user's row is **404, never 403** | whether an id exists is not something to leak |
| Measurement bounds: weight `> 0`, body fat `>= 0` | asymmetric in the Python schema, deliberately |
| Water entries have **no update endpoint** | create and delete only |
| List returns a **summary shape** with `exercise_count`, not the children | different shape from the detail endpoint |

### Query design

- **Workout list** aggregates `count(e.id)` in SQL rather than loading each workout's
  children to call `size()`. Uses `ix_workouts_user_performed (user_id, performed_at)`
  for both the filter and the sort.
- **Workout detail** is one query with the children and their library rows fetch-joined.
  Without the joins it is 1 + 1 + N.
- **Writing a workout** resolves every referenced exercise in one `findAllById`, not a
  lookup per entry.
- **Daily nutrition summary** is two aggregate queries with `COALESCE`, so an empty day
  returns `0`/`0.0` rather than nulls.
- `spring.jpa.open-in-view=false` throughout. Every collection a response needs is
  fetched explicitly; a lazy access after the service returns fails loudly rather than
  silently issuing per-row queries.

**No new indexes.** The existing ones cover every query these domains issue.

`WorkoutQueryEfficiencyTest` counts the statements Hibernate actually issues via
`hibernate.generate_statistics`. "Avoid N+1" is easy to assert in review and easy to
regress silently; counting statements turns a regression into a build failure.

### Four contract bugs the tests caught

Worth recording because each would have shipped:

1. **Enums serialised as `OTHER`, not `other`.** Java constants are uppercase; the wire
   format is lowercase. Fixed with `@JsonValue`/`@JsonCreator` on every `PgEnum`.
2. **`?category=cardio` returned 400.** Jackson's naming strategy governs request bodies,
   not URL binding — Spring's default `String → Enum` converter matches `Enum.name()`.
   Fixed with a `ConverterFactory` covering every `PgEnum` at once.
3. **`?date_from=` was never bound.** Query parameter names are not touched by the
   snake_case strategy either; each is now named explicitly with `@RequestParam(name=...)`.
4. **`WHERE (:dateFrom IS NULL OR ...)` failed with "could not determine data type of
   parameter".** PostgreSQL cannot infer a parameter's type from `? IS NULL` alone; fixed
   with `cast(:dateFrom as LocalDate)` rather than splitting into four near-identical
   queries.

### Phases 3–6 results

| Gate | Result |
|---|---|
| `./mvnw verify` | **137 tests, 0 failures** |
| Cross-backend response comparison | **38/38 identical** |
| Frontend against Java — workouts | 13/13 |
| Frontend against Java — nutrition, progress, profile, dashboard | 21/21 |

The frontend checks ran the real Next.js pages through nginx with the BFF pointed at
`api-java`: meal logging and the daily summary, the weight chart and goals, the profile
form pre-populating, and the dashboard reading across all three domains. Routing was
restored to Python afterwards.

**One thing the frontend run surfaced that is worth knowing:** the containers run on UTC
and the app derives "today" from UTC (`todayIso()` uses `toISOString()`). A first pass
using local dates logged meals on the previous day and the "today" panels correctly
showed nothing. That is existing behaviour shared with the Python backend, not something
the migration introduced — but it is a real edge for users west of UTC near midnight.

## 8.9 Phase 7 — adaptive training insights

The project's differentiator, and the port where "close enough" is not good enough: the
output is numbers users act on, and the frontend renders evidence strings verbatim.

### All six classifications preserved

`progressing`, `stable`, `possible_plateau`, `declining`, `insufficient_data`,
`not_applicable` — with the same evaluation order, which is itself a rule: plateau is
checked **before** progression so an older jump followed by four flat sessions is still a
plateau, and the 14-day span requirement is what stops four sessions in one week being
called one.

The LLM takes no part in any of it. Classification, metrics, personal bests and
consistency are computed in `traininginsights/domain/TrainingAnalytics` — a pure class
with no Spring annotations, no clock and no I/O.

### Rounding: measured, not assumed

CPython's `round()` is round-half-to-even over the **exact binary value** of the double.
Three Java spellings were compared against the Python implementation before any code was
written:

| value | Python `round(v,2)` | `Math.round`-style | `BigDecimal.valueOf` | `new BigDecimal` |
|---|---|---|---|---|
| 2.675 | **2.67** | 2.68 | 2.68 | **2.67** ✓ |
| 2.665 | **2.67** | 2.67 | 2.66 | **2.67** ✓ |
| 12.345 | **12.35** | 12.35 | 12.34 | **12.35** ✓ |
| 2.5 → `round(v)` | **2** | 3 | — | **2** ✓ |

`BigDecimal.valueOf(double)` goes through `Double.toString`, which yields the shortest
round-tripping decimal ("2.675") and then rounds *that* — landing on 2.68 where Python
sees the true value 2.67499…  `PythonNumbers` therefore uses `new BigDecimal(double)` with
`HALF_EVEN`, and `Math.round` appears nowhere in the engine.

**But the helper is not trusted on the strength of that table.** The fixtures below are
the authority; if the two ever disagree, the build fails with the exact field.

### Language-neutral fixtures

`contract/generate_training_fixtures.py` runs the **Python engine** and records the full
computed result — every metric, every evidence line, the suggestion, the fallback
explanation, the session aggregation and the personal bests. One file,
`contract/training-insights-fixtures.json`, is consumed by both:

| Consumer | Assertions |
|---|---|
| `backend/tests/unit/test_training_fixture_parity.py` (pytest) | **92** |
| `backend-java/.../TrainingAnalyticsFixtureTest.java` (JUnit `@ParameterizedTest`) | **92** |

Running it against Python too is not redundant: it guarantees the fixtures still describe
the current engine, so a change to `analytics.py` fails there rather than silently making
the Java port "wrong" against a stale expectation.

**30 history cases** covering insufficient history, missing weights, bodyweight and
zero-weight exercises, mixed weighted/unweighted, varying sets, varying reps, same-day
sessions, unordered input, a repeated exercise within one workout, custom exercises, the
five-session window cap, empty history, the medical-note suppression, all four plateau
guard rails, the 2.0% threshold from both sides, and four deliberate rounding-boundary
cases. Plus **31 scalar checks** on `percent_change`, `suggest_next_load_kg`,
`compute_consistency` and `compute_volume_trend`.

A fixture-count guard asserts all six classifications appear, so a case cannot be quietly
dropped.

### What the fixtures caught

A real contract bug that unit tests on either side alone would have missed:

```
expected: {"workouts_last_7_days": 3, ...}   # Python
but was:  {"workouts_last7_days": 3, ...}    # Java
```

Jackson's snake_case strategy renders `workoutsLast7Days` as `workouts_last7_days` —
Python inserts a separator before a digit run and Jackson does not. The same applied to
`current_7_day_volume_kg`. Every *value* was correct; only the keys differed, so the
frontend would have read `undefined` and silently shown nothing. Both DTOs now pin the
names with `@JsonProperty`.

The API tests also surfaced a gap in the shared exception handler: `@Min`/`@Max` on a
`@RequestParam` throws `ConstraintViolationException`, which took a different path from
body validation and escaped as a 500. An out-of-range `?limit=` now returns the 400
envelope like everything else.

### Query design

Three queries, none per-exercise. The overview joins every exercise at once and groups in
Java, so a seventh exercise in a user's routine does not add a seventh round trip. Workout
dates are queried separately because a workout logged with no exercises still counts as a
session, and an inner join would drop it. Ownership is in the SQL: a foreign
`exercise_id` yields an empty result, never another user's history. **No new indexes** —
`ix_workouts_user_performed` and `ix_workout_exercises_workout_id` already cover it.

### Phase 7 results

| Gate | Result |
|---|---|
| `./mvnw verify` | **245 tests, 0 failures** |
| Python suite | **234 passed** (142 existing + 92 shared-fixture) |
| `ruff check` | clean |
| Shared fixtures, Python ↔ Java | 92 ↔ 92, identical file |
| Cross-backend response parity | **47/47 identical** across all five domains |
| Training Insights UI against Java | 18/18 |

Phase 7 changed **no Python application code** — the only addition under `backend/` is the
new fixture-parity test. nginx and the frontend are untouched, and production traffic
still goes to Python.

## 8.10 Phase 8 — the AI layer

The rule from the adaptive-training work carries straight through: **the model explains,
it never computes.** Phase 7 made every classification and metric deterministic. Phase 8
adds a provider that can put prose around those numbers, and takes care that it can never
change them.

### Provider boundary

`AiProvider` is an interface with four methods (`complete`, `completeJson`, `isConfigured`,
and a `Message` record). `OpenAiProvider` is the only implementation; tests use
`FakeAiProvider`. That split exists so no automated test can reach the paid API — there is
no code path from the test profile to a real endpoint, rather than a convention that
someone has to remember.

Failures are typed rather than generic. `AiProviderException.Kind.UNAVAILABLE` becomes a
503 and `Kind.BAD_RESPONSE` becomes a 502, both matching what Python returns today. A
model that emits malformed JSON is the service's problem to absorb, not the client's to
decode: `readValidated()` turns it into a 502 with a generic message. No stack trace, SQL
error or provider detail reaches the browser.

### Four things that are deliberate

**Explicit timeouts, not virtual threads.** §8.5 already recorded that virtual threads are
not a substitute for timeouts, connection limits or failure isolation. So the provider
sets a connect timeout *and* a read timeout from `app.ai.timeout`, and bounds concurrency
with a `Semaphore` sized by `app.ai.max-concurrent-requests`. The semaphore **fails fast
rather than queueing** — a saturated provider should shed load and return 503 quickly, not
accumulate a backlog of requests that will time out anyway.

**No database transaction is held across a provider call.** `AiService` is deliberately
*not* `@Transactional`. It reads what it needs, closes the transaction, then calls the
provider. A slow model must not pin a Hikari connection for the length of its response.

**The explanation layer cannot alter a verdict.** `TrainingInsightExplainer` takes the
already-computed `ExerciseInsight`, asks the model to rewrite only the prose, and swaps in
the result. If the provider throws, it catches `AiProviderException` and returns the
insight **unchanged** — `explain=true` degrades to deterministic prose instead of failing.
The response carries `explanation_source` (`ai` or `deterministic`) so a caller can always
tell which it got. The classification, the metrics and the evidence strings are never in
the model's reach.

**Prompt scoping is a security boundary, not a convenience.** `findVisibleForPrompt` limits
what user content can enter a prompt. Prompts are never logged; the provider logs the
failure cause and the metric names only.

### `JdkClientHttpRequestFactory`, and why

The first stub run failed with `ResourceAccessException: Unexpected end of file from
server`. The cause was on both sides. The stub was a single-threaded HTTP/1.0 server, and
`SimpleClientHttpRequestFactory` (`HttpURLConnection`) streams request bodies chunked and
has long-standing quirks around connection reuse. The stub became a
`ThreadingHTTPServer` speaking HTTP/1.1 with a chunked-aware body reader, and the provider
moved to `JdkClientHttpRequestFactory` over `java.net.http.HttpClient`. That is the right
default for a modern Spring client regardless; the stub simply surfaced it early.

### Metrics carry no user data

`ai.request.duration`, `ai.request.failed` and `ai.request.rejected` are tagged by outcome
only. No user ID, workout ID, email or free-text exercise name appears in a label — those
are unbounded cardinality and, here, private fitness data.

### How the AI path was validated without paying for it

A deterministic stub OpenAI server, run on the host and pointed at by **both** backends
through a compose override, returns scripted completions. Because both backends talk to
the same stub, `compare_backends.py --suite ai` compares like with like, and the
comparison includes the assertion that matters most: with `explain=true`, only the prose
differs — `classification`, metrics and evidence are byte-identical to `explain=false`.

The override lives outside the repository and was removed afterwards, so no configuration
in git contains a provider key, real or fake. With no key configured, both backends return
503 from the AI endpoints while every non-AI endpoint is unaffected.

### A hazard worth writing down

`backend/tests/conftest.py` defaults `DATABASE_URL` to
`postgresql+asyncpg://fitforge:fitforge@localhost:5432/fitforge`, and the `db_engine`
fixture runs `Base.metadata.drop_all` at both setup and teardown. That default is the same
address the Docker dev database publishes. Running the Python suite **without** exporting
`DATABASE_URL` at a throwaway database therefore drops every table in the dev database —
which is exactly what happened once during this phase.

It is recoverable (`alembic stamp base && alembic upgrade head` rebuilds the schema, and
migration `007_expand_exercise_seed` restores the seeded exercise library), and it also
re-exercises the documented baseline path: Flyway finds its existing baseline row on an
Alembic-built database and correctly declines to replay V1. But the safe habit is to
always aim the suite somewhere disposable:

```
DATABASE_URL=postgresql+asyncpg://…/<throwaway> python -m pytest
```

Note also that the suite must be run from `backend/`; from the repository root pytest
resolves a different rootdir and the database fixtures never load.

### Phase 8 results

| Gate | Result |
|---|---|
| `./mvnw verify` | **262 tests, 0 failures** |
| Python suite | **234 passed** |
| `ruff check` | clean |
| Cross-backend response parity | **55/55 identical** across six suites |
| AI Coach UI against Java (BFF override) | 11/11 |
| Degradation with no provider configured | 503 on AI endpoints, core endpoints unaffected, no internals leaked |

Phase 8 changed **no Python application code**. nginx and the frontend BFF were pointed
back at Python afterwards, and production traffic still goes to Python.

## 8.11 Phase 9 — background processing

Celery's replacement is Redis Streams. Redis was already deployed and was already Celery's
broker, so this adds no infrastructure; Kafka or RabbitMQ would be a new deployable for two
email types.

### Where this is stronger than Celery, deliberately

Worth being precise, because "it uses a queue" is not a design, and the Python behaviour is
weaker than people assume. `celery_app.py` sets no `acks_late`, and neither task in
`app/modules/auth/tasks.py` calls `self.retry()`. So today: **a message is acked before it
runs** (a worker crash loses it) and **a failed send is never retried**.

The Java worker acks only after the send returns, and `XAUTOCLAIM` reclaims entries left
pending by a dead consumer. That is at-least-once rather than at-most-once. It is safe
here because the payload is a single-use token that the database enforces as single-use, so
a duplicate verification email is harmless. This is an intentional improvement, not parity —
recorded as such so nobody later "fixes" it back.

| | Celery (Python) | Redis Streams (Java) |
|---|---|---|
| Ack timing | before execution | after the send returns |
| Crashed worker | message lost | reclaimed by `XAUTOCLAIM` |
| Failed send | no retry | retried with exponential backoff |
| Poison message | n/a | dead-lettered after `max-attempts` |
| Enqueue during broker outage | logged, request succeeds | logged, request succeeds |

That last row *is* parity, and it matters: email is the non-essential half of registration
and password reset. Both backends let the request succeed without it, because the user can
recover through resend-verification.

### Two defects found while reviewing this phase

Writing the phase up meant re-reading code written earlier, and two things did not survive
the reading.

**Retries exhausted in about five seconds.** A failed send was re-added to the stream
immediately, and the poll interval is one second — so five attempts were spent in five
seconds. Against the failure retries exist for, a mail host that is briefly unreachable,
that is no defence at all. A stream cannot express "not before T", so failed messages now
wait in a sorted set scored by their due time, and `promoteDueRetries()` moves them back
when due. Backoff is exponential from `app.email.retry-backoff` (10s), capped at
`app.email.retry-backoff-max` (10m). Removal from the set happens *before* the re-add: that
risks dropping one retry if Redis fails in between, which is better than replaying the
message on every tick if the removal fails after.

**Dead-lettered messages retained a live one-time token.** The dead-letter stream is
written and never drained, so anything put there stays indefinitely — and the copied
payload included the raw verification token. The recipient is still recorded, because an
operator needs to know who was affected; the token is now replaced with `(redacted)`, and
the stream is trimmed to the same bound as the live one. A dead-lettered message is never
delivered, so the token had no remaining use and was pure liability.

Both are covered by tests that fail if the behaviour regresses.

### Known sharp edge

`reclaimStalled()` claims from the group's whole pending list, not just other consumers'
entries. `XCLAIM minIdle` normally prevents a worker from reclaiming its own in-flight
message, but a send that runs longer than `app.email.claim-after` (2m) could be reclaimed
and re-sent by the same worker. Idempotency absorbs it. Keep `claim-after` comfortably
above the SMTP timeout.

### Phase 9 results

| Gate | Result |
|---|---|
| `EmailDispatchTest` | **8 tests, 0 failures** (was 6) |
| New coverage | delayed retry; dead-letter carries no token |

## 8.12 Phase 10 — frontend parity

The frontend was never modified for the migration. That is the point: if the Java backend
is a faithful replacement, pointing the Next.js BFF at it should require changing one
environment variable and nothing else. Phase 10 tested exactly that, by setting
`BACKEND_INTERNAL_URL` to `http://api-java:8000/api` and re-running every scripted flow
built during Phases 2–8.

| Flow | Checks against Java |
|---|---|
| Auth and session through the BFF | 21 |
| Workouts | 13 |
| Nutrition, progress, profile | 21 |
| Adaptive training insights | 19 |
| AI coach | 11 |
| **Total** | **85, 0 failures** |

`npm run lint` is clean and `npm run build` produces a production build. **There is no
frontend test suite** — `package.json` defines only `dev`, `build`, `start` and `lint`.
The 85 scripted HTTP checks are the frontend's entire regression coverage, and that is a
real gap rather than a thing to paper over: they exercise rendered output and BFF routes,
but no component behaviour, and nothing catches a client-side regression that still
server-renders correctly.

### A defect in the comparison harness

Running the full comparison with no provider configured reported two differences in the
explain path, presented as `python='ai' java='deterministic'`. Both backends were correct
and identical; the harness was wrong. `compare_backends.py` hard-coded
`explanation_source == "ai"` as the expected value — an assumption baked in while the AI
stub happened to be running — and printed its own expectation in the "python" column, which
made a harness bug look like a backend divergence.

The check now compares the two backends against each other and asserts separately that
neither let the model change a computed field. It no longer cares whether a provider is
configured, because the invariant does not depend on that.

This is worth recording for a reason beyond the fix: a comparison harness that encodes a
precondition as an expected value will eventually report a false difference, and a false
difference is expensive — it costs the time to disprove, and it trains you to distrust the
one tool whose credibility the whole migration rests on.

### Phase 10 results

| Gate | Result |
|---|---|
| Frontend flows against Java | **85/85** |
| `npm run lint` | clean |
| `npm run build` | succeeds |
| Cross-backend parity, no provider | **56/56 identical** |
| Cross-backend parity, provider configured | **56/56 identical** |

Routing was returned to Python afterwards. The frontend has no committed reference to the
Java service.

## 8.13 Phase 11 — production cutover preparation

A cutover you cannot reverse in seconds is not a cutover, it is a migration with extra
steps. So the goal here was not "switch to Java" — it was to make switching, in either
direction, a variable change that needs no file edit, no rebuild and no redeploy.

### The switch

nginx's API upstream is now `server ${API_UPSTREAM};`, and the config is mounted as
`/etc/nginx/templates/default.conf.template` so the official image's `envsubst` step
resolves it at container start. envsubst only substitutes `${NAME}` with braces, so
nginx's own `$host`, `$remote_addr` and `$proxy_add_x_forwarded_for` pass through
untouched.

Two variables move together, and both must, or the browser and the server-rendered pages
will be talking to different backends:

| | Python (default) | Java |
|---|---|---|
| `API_UPSTREAM` | `api:8000` | `api-java:8000` |
| `BACKEND_INTERNAL_URL` | `http://api:8000/api` | `http://api-java:8000/api` |

```
# cut over
API_UPSTREAM=api-java:8000 BACKEND_INTERNAL_URL=http://api-java:8000/api \
  docker compose up -d --force-recreate nginx frontend

# roll back — identical command, previous values (or just unset both)
docker compose up -d --force-recreate nginx frontend
```

nginx now also waits on `api-java` being healthy, not just `api`. Without that, a cutover
could point the upstream at a container that is still starting.

The database is deliberately *not* part of the switch. Both services share one schema, one
Redis and one `SECRET_KEY` — which is what makes rollback safe: a token minted by either
backend validates against the other, so moving traffic does not log anyone out, and moving
it back does not either.

### Verified, not assumed

Both directions were exercised against the running stack:

| Step | Upstream | BFF | register / dashboard / workouts |
|---|---|---|---|
| Baseline | `api:8000` | Python | 201 / 200 / 200 |
| Cutover | `api-java:8000` | Java | 201 / 200 / 200 |
| Rollback | `api:8000` | Python | 201 / 200 / 200 |

### Response headers are not identical, and that is fine

Bodies and status codes match on all 56 compared responses, but the two backends do not
emit identical headers. Java adds what Spring Security adds by default:

```
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Expires: 0
Pragma: no-cache
Vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 0
```

Java also uses `Transfer-Encoding: chunked` where FastAPI sends `Content-Length`, and its
status line is `HTTP/1.1 200` rather than `HTTP/1.1 200 OK` (the reason phrase is optional
and no client parses it).

Every one of these is additive or cosmetic, and the security headers are strictly an
improvement on an API that previously sent none. The 85 frontend checks pass against both.
Recorded in `docs/api-compatibility.md` as an accepted deviation rather than silently
tolerated, because "the responses are identical" should not be said loosely.

### Python is still here

Nothing was deleted. `api`, `worker` and `beat` still build, still run and still serve by
default. Removing them is a separate decision that needs explicit approval and should not
happen until the Java service has held production traffic long enough to be boring.

## 8.14 Phase 12 — observability

Actuator, Micrometer, Prometheus and Grafana, wired so that the Phase 13 load tests have
something to read.

### Exposure

`/actuator/**` lives on management port 9000, and **9000 is not published to the host**.
Prometheus scrapes it over the Compose network; nothing outside that network can reach it.
Only `health`, `info`, `prometheus` and `metrics` are exposed even there.

Prometheus (9090) and Grafana (3001) are bound to `127.0.0.1`. Prometheus has no
authentication at all and Grafana ships with a default password, so neither may listen on a
public interface. Grafana has anonymous access and sign-up disabled, and its password comes
from `GRAFANA_PASSWORD`.

### No user data in labels, verified rather than asserted

The rule was that metric labels must not carry user IDs, workout IDs, emails or free-text
exercise names. Checking the live scrape output:

```
uri="/api/v1/training/exercises/{exerciseId}/insights"
uri="/api/v1/training/exercises/{exerciseId}/history"
```

Path variables are templated by Spring, and a grep of the entire exposure for UUID-shaped
or email-shaped strings returns zero lines. The custom meters (`email.*`, `ai.*`) carry no
dimensions beyond outcome.

This matters for more than privacy: a label with one value per user is one time series per
user, which is the standard way to destroy a metrics backend.

### Histograms, not pre-computed percentiles

`management.metrics.distribution.percentiles-histogram.http.server.requests=true` exports
bucket counts. The alternative, Micrometer's `percentiles=` property, computes quantiles
inside a single JVM — those cannot be aggregated across instances and cannot be re-sliced
after collection. Buckets let Prometheus compute `histogram_quantile()` over any window and
any grouping, which is what a load test needs.

Buckets are bounded to eight SLO boundaries (10ms → 2s) on purpose: every boundary is a new
series per uri/method/status.

### Dashboard

Ten panels, provisioned from `monitoring/grafana/dashboards/fitness-tracker-java.json`:
request rate by endpoint, latency percentiles, error rate, slowest endpoints, JVM heap, GC
pause, HikariCP pool, connection acquisition time, email queue, AI provider.

Two are there specifically to diagnose the load tests. `hikaricp_connections_pending` above
zero means requests are queuing for a database connection — the pool, not the code, is the
limit. `ai_request_rejected_total` rising means the provider semaphore is shedding load,
which is the design working, not an outage.

The datasource is pinned to `uid: prometheus`. Without that, Grafana generates a random uid
per install (`PBFA97CFB590B2093` on the first run here) and every panel renders "datasource
not found" — caught by checking the provisioned dashboard through the API rather than
assuming provisioning had worked.

### Asymmetry worth stating

The Python backend exposes no metrics endpoint. Any latency comparison between the two
backends therefore has to come from the load generator's own measurements, not from
Prometheus. Phase 13 does it that way.

### Phase 12 results

| Gate | Result |
|---|---|
| Prometheus target `fitness-tracker-java` | `up` |
| Latency buckets exported | 77 series |
| Dashboard provisioned | 10/10 panels, datasource resolved |
| Sample queries return data | rate, `histogram_quantile` p95, heap, Hikari — all non-empty |
| User data in labels | none found |

## 8.15 Phase 13 — performance testing

### k6, not Gatling

Gatling is the more natural fit on paper: a JVM tool for a JVM service, a Maven plugin, a
typed DSL that could share code with the JUnit suite. It was not chosen, for one reason
that outweighs all of that here — **the load generator and the service under test share one
laptop.** Gatling would put a second JVM, with its own heap and its own collector, beside
the one being measured, and JVM-on-JVM contention is exactly the noise that makes a p99
untrustworthy. k6's VUs are goroutines, its per-VU footprint is far smaller, and it runs as
its own container under an explicit `--cpus` limit.

Secondary reasons: the scenarios are plain HTTP flows already expressed in the same shape by
the bash validation scripts, and k6 emits p50/p95/p99 plus a machine-readable summary
directly, so nothing sits between the run and the recorded numbers.

The trade-off accepted: Gatling's reports are richer and its DSL is better typed. Neither
outweighs measurement fidelity.

### Dataset

`perf/seed.sql` builds 100 users, each with a year of history — 10,000 workouts, 40,000
workout-exercises, 30,000 meals, 6,000 body measurements, 100 profiles. Seeded with
`generate_series` rather than through the API, because the same data over HTTP would take
hours and would measure the seeding.

Every load-test user shares one password and therefore one Argon2 hash — computed with the
**same** parameters the application uses (m=65536, t=3, p=4), so login still pays the real
verification cost. Shortcutting that would make the auth numbers meaningless.

### Three runs, and why the first two are not results

Reporting only the final number would hide the more useful part.

| Run | Failures | Cause | Whose fault |
|---|---|---|---|
| 1 | 46.6% | setup-minted tokens aged past the 15-minute TTL; one request hung 1,211s, stretching a 4m45s profile to 25 minutes | harness |
| 2 | 21.9% | `/nutrition/summary` called without its required `date` | harness — but it exposed a real server bug |
| 3 | **0.000%** | — | — |

Run 1 produced a `max` of 1,211,407ms and a p99 of "n/a". Those were discarded rather than
published. The fixes were a hard 30s per-request timeout (so "hung forever" becomes a
recorded failure instead of a poisoned statistic), a per-VU token refreshed on 401, and
`summaryTrendStats` extended to include p(99), which is not in k6's defaults.

Run 2's 21.9% was 12,383 responses from one endpoint. That is the finding described in
§8.16 — the load test's malformed request met a server that answered 500 instead of 400.

### Baseline — measured, 2026-09-19

Commit `3111596` (working tree dirty), Darwin arm64, 10 CPUs / 16 GB host, Docker limit 10
CPUs / 7 GB, k6 0.53.0 in-container at 4 CPUs, backend `api-java` on the Compose network.
Profile: arrival-rate ramp 20 → 300 req/s over 4m45s, plus a constant 3 req/s login
scenario. 56,460 requests.

| Metric | Value |
|---|---|
| Throughput | 197.6 req/s |
| Failures | **0.000%** |
| p50 | 0.92 ms |
| p95 | 1.97 ms |
| p99 | 106.99 ms |
| max | 443.57 ms |

Per endpoint, p95 / p99:

| Endpoint | p95 | p99 |
|---|---|---|
| `workout_detail` | 1.05 ms | 1.90 ms |
| `measurements` | 1.26 ms | 2.69 ms |
| `nutrition_summary` | 1.26 ms | 2.79 ms |
| `profile` | 1.39 ms | 2.52 ms |
| `workouts_list` | 1.86 ms | 3.64 ms |
| `training_insights` | 2.00 ms | 4.03 ms |
| `training_overview` | 2.16 ms | 4.18 ms |
| `login` | **136.49 ms** | **273.30 ms** |

### The p99 is Argon2, and that is correct

Overall p99 (107ms) is 54× overall p95 (1.97ms), which looks alarming until it is
decomposed. Every read endpoint's p99 is under 4.2ms. Login's p99 is 273ms. Login is ~1.6%
of requests, which places it exactly at the p99 boundary — so the aggregate p99 *is* the
login latency, and login latency is deliberate: Argon2id at m=65536,t=3,p=4 is meant to cost
about that much. Making it faster would mean weakening password hashing.

This is why the aggregate number alone would have been misleading, and why per-endpoint
trends are recorded separately.

### Nothing saturated

| Resource | Peak during the run | Limit |
|---|---|---|
| HikariCP pending | **0** | — |
| HikariCP active | 6 | 10 |
| Process CPU | 17.8% | 100% |
| System CPU | 20.3% | 100% |
| GC pause (total) | 3.20 s over 10 min | — |
| Dropped iterations | 11 | — |

At 300 req/s the service is nowhere near a limit, so this profile cannot identify a
bottleneck. Finding one requires pushing past saturation — see §8.16.

## 8.16 Phase 14 — the measured bottleneck

Full write-up in [docs/performance-analysis.md](performance-analysis.md). In brief:

`AuthService.login` was `@Transactional`, so it held a pooled connection across Argon2id
verification — ~100ms of CPU touching no database. Under load that monopolised the pool and
every other endpoint queued behind it (3,285 threads pending, 3,775 acquisition timeouts,
30.9s worst-case acquire).

Two related defects surfaced with it: `issueTokenPair`'s `@Transactional` was a no-op
because `login` invoked it on `this` (self-invocation never crosses the Spring proxy), and
pool exhaustion was returning 500 rather than 503.

Measured on `perf/k6/login-contention.js`, pool pinned to 10 on both sides so only the
annotation differs:

| Metric | BEFORE | AFTER | Change |
|---|---|---|---|
| READ p95 | 4.03 ms | 2.41 ms | **−40.1%** |
| READ p99 | 9.92 ms | 6.06 ms | **−38.9%** |
| LOGIN p99 | 438.83 ms | 367.46 ms | −16.3% |
| Throughput | 206.6 req/s | 206.6 req/s | — |
| Failures | 0.000% | 0.000% | — |

Four earlier measurements were discarded as invalid, and the reasons are documented rather
than hidden — including one run whose only purpose was to check whether an apparent 6x
regression was real. It was not: re-running the *unchanged* configuration performed worse
still, which proved the 5,000 req/s regime was measuring the laptop rather than the code.

## 9. Highest-risk areas

Ordered by expected cost of getting it wrong.

**R1 — Refresh-token reuse detection and transaction boundaries. — RESOLVED in Phase 0.5.**
The Python code deliberately commits the mass-revocation *before* raising the 401,
because its error handler rolls the request transaction back — a flush alone would be
undone by the very error reporting the reuse. This is the exact bug that was fixed in
commit `6c32800`, so it has already bitten this codebase once.

Spring's `@Transactional` rolls back on `RuntimeException` identically. Naively porting
this produces a backend that *detects* reuse and *reports* it while silently failing to
revoke anything — a security regression that no status-code test would catch.
Mitigation: perform the revocation in a `REQUIRES_NEW` transaction that commits on its
own, then throw. The contract test must assert the sibling tokens are actually revoked
in the database, not merely that the response was 401.

**R2 — Argon2 hash compatibility. — RESOLVED in Phase 0.5.**
Existing users' `password_hash` values were produced by `argon2-cffi`'s defaults
(argon2id, t=3, m=65536, p=4, 32-byte hash, 16-byte salt). Spring's
`Argon2PasswordEncoder` parses parameters out of the encoded string, so *verification*
should work — but its own defaults differ (m=4096, p=1), so hashes it *creates* would
be weaker than the existing ones and would differ in cost.

During the side-by-side period both backends may write hashes the other must read.
Mitigation: construct the encoder explicitly with argon2-cffi's parameters, and add a
test that verifies a **real hash produced by the Python implementation** — committed as
a fixture — rather than only round-tripping within Java.

**R3 — Native PostgreSQL enums under `ddl-auto=validate`. — RESOLVED in Phase 0.5; the originally proposed mapping was wrong.**
Five columns are native PG enum types, not varchar. `@Enumerated(STRING)` fails on write
(`column is of type user_role but expression is of type character varying`). Hibernate 6's
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)` is the intended mapping, but whether `validate`
accepts it against a `USER-DEFINED` column is **not something I will assert without
testing**. Phase 1 spikes exactly this, before any entity is written. Fallbacks, in
order of preference: `NAMED_ENUM`; a `@ColumnTransformer` write expression with a
`::user_role` cast; a custom `UserType`.

**R4 — Timestamp serialisation fidelity.**
Python emits six fractional digits always; `Instant.toString()` trims trailing zeros.
`…51.100000Z` becomes `…51.1Z`. Both parse correctly in JavaScript, so this is accepted
as a documented deviation rather than engineered around — but it must be a *decision*,
not a surprise found during cutover.

**R5 — Async-to-blocking under load.**
FastAPI handles a slow OpenAI call on an event loop; Spring MVC occupies a request
thread for the full 20-second timeout. Mitigated by virtual threads, but the mitigation
is unproven for this workload until Phase 12 measures it. No performance claim will be
made before then.

**R6 — Validation message parity.**
Jakarta's messages cannot match Pydantic's text. Accepted deviation: preserve the
structure, the `field` and the `code`; do not attempt to reproduce the prose. Confirmed
safe because the frontend never matches on message text.

**R7 — Ownership checks.**
Python enforces ownership per query in the service layer. There is no framework
safety net, so every single query must be ported with its `user_id` predicate intact.
An omission leaks another user's fitness data and returns 200. Mitigation: a
cross-user isolation test for every user-owned endpoint, not just a representative one —
the pattern already used in `test_training_insights.py`.

**R8 — Analytics floating-point parity.**
Both languages use IEEE-754 doubles, so arithmetic should agree, but rounding helpers
differ: Python's `round()` is banker's rounding, and `Math.round()` is not. The engine
rounds percentages to one decimal in several places. Shared fixtures (§8) with values
deliberately placed on rounding boundaries are the mitigation.

**R9 — `POST /exercises` duplicate-name race.**
Python uses a SAVEPOINT so losing the race does not poison the surrounding transaction.
The Java equivalent needs the same care — a `DataIntegrityViolationException` marks the
Spring transaction rollback-only, so the recovery read must happen in a new transaction.

---

## 10. Deployment during migration

`docker-compose.yml` gains a second API service. Both run; only one receives traffic.

```yaml
api:        # existing FastAPI — unchanged, still the reference
api-java:   # new Spring Boot, same DATABASE_URL, same REDIS_URL, same SECRET_KEY
```

Sharing `SECRET_KEY` is what makes a gradual cutover possible: a token minted by either
backend validates against the other, so switching nginx does not log every user out.

nginx keeps pointing `/api/v1/` at `api` until parity is proven, then flips the single
`upstream` to `api-java`. Because the split is on the `/v1/` segment
([ADR 005](adr/005-nginx-path-routing-split.md)), this is a one-line change and the
frontend needs no modification at all.

Rollback is the same one-line change in reverse, which is the point of keeping Python
running.

CI gains a Java job (`./mvnw verify` with Testcontainers) alongside the existing Python
and frontend jobs. **The Python job is not removed until Phase 11.**

---

## 11. Success criteria

Migration is complete when all of these hold — not before:

1. Existing frontend works against Java with **zero frontend changes**
2. Auth: register, login, refresh, rotation, reuse detection, verification, reset, logout
3. Workouts, 4. Nutrition, 5. Progress, 6. Profile — all CRUD + ownership
7. AI coach: all four endpoints, including the 502/503 degradation contract
8. Adaptive analytics: identical classifications on shared fixtures
9. PostgreSQL schema unchanged; `validate` passes
10. Background email works, including crash recovery
11. CI green: Java, Python and frontend
12. Integration tests pass on Testcontainers PostgreSQL
13. `docker compose up --build` works
14. nginx routes correctly to Java
15. `/api/v1/health` and `/ready` behave identically
16. No Python-only functionality remains

Only then does retiring `backend/` get proposed — as its own reviewable change.

---

## 12. What this plan does not do

- Does not change the frontend.
- Does not change the database schema.
- Does not redesign any working behaviour, including the quirks: 201-on-existing-exercise,
  upsert-returns-201, idempotent logout, always-200 forgot-password.
- Does not add Kubernetes, Kafka, a service mesh, microservices, CQRS, event sourcing,
  Elasticsearch, a vector database or GraphQL.
- Does not add a second LLM vendor.
- Does not claim any performance number. Phase 12 measures; until then there is nothing
  honest to say about throughput or latency.

## 8.17 Phase 15 — cutover

Java became the default in committed configuration: `API_UPSTREAM` defaults to
`api-java:8000` and `BACKEND_INTERNAL_URL` to `http://api-java:8000/api`, in both
`docker-compose.yml` and `docker-compose.prod.yml`.

Routing was verified by counter rather than by assertion: five requests through nginx
incremented `api-java`'s `http_server_requests_seconds_count` for `/api/v1/workouts` by
exactly five, while the Python container's access log recorded none.

**Post-cutover validation: 72/72** against the Java-routed stack — health and readiness,
registration and login, refresh rotation with reuse detection, workouts, nutrition,
progress, profile, training insights, AI enabled (against a local stub) and AI unavailable,
the Redis Streams email path end to end into Mailhog, Prometheus and Grafana, and every
frontend page and BFF route.

**Rollback smoke test, exercised in both directions:** Java → Python → Java. An account
created on Java logged in on Python, and an account created on Python logged in on Java —
which is the property that makes rollback non-destructive, and the reason both services must
keep sharing one database, one Redis and one `SECRET_KEY`.

The Python backend, `worker` and `beat` were **not** deleted. They still build and run.
