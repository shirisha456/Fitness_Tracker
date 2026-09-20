# backend-java

The application backend: **Java 21 / Spring Boot 3.5**. nginx routes `/api/v1/*` here and
the Next.js BFF calls it directly over the Docker network.

## Running it

```bash
# Full test suite. Needs Docker — integration tests use Testcontainers against real
# PostgreSQL and Redis.
./mvnw verify

# Locally, against the Compose database and Redis
docker compose up -d db redis
./mvnw spring-boot:run

# In the full stack
docker compose up -d                 # from the repository root
docker compose logs -f api-java
curl localhost/api/v1/health         # through nginx
curl localhost:8001/api/v1/health    # direct
```

`/actuator/**` lives on management port **9000**, which is not published to the host.
Prometheus scrapes it over the Compose network; nothing outside that network can reach it.

## Layout

```
src/main/java/com/fitnesstracker/
├── common/
│   ├── api/          ApiResponse, ErrorResponse, ErrorCode — the wire envelopes
│   ├── exception/    AppException, GlobalExceptionHandler
│   ├── persistence/  PgEnumUserType — native PostgreSQL enum binding
│   └── web/          CorrelationIdFilter
├── config/           Jackson, password encoder, clock
├── health/           /api/v1/health and /api/v1/ready
├── auth/             entities and token rotation (partial — Phase 2)
├── workouts/         Exercise entity only (partial — Phase 3)
├── traininginsights/ deterministic analytics — pure domain, no provider calls
└── ai/               provider boundary, AiService, TrainingInsightExplainer
```

## Things that will bite you if you change them

**`spring.jpa.hibernate.ddl-auto=validate`.** The schema belongs to Flyway (and
historically to an earlier migration tool). Never `update` or `create`, in any profile.

**Native enum columns need `PgEnumUserType` + `columnDefinition`.** The obvious mapping,
`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, *passes schema validation and then fails on every
insert*, because it binds `Enum.name()` (uppercase) against lowercase database labels.
Measured in Phase 0.5; `EnumMappingSpikeTest` guards it.

**`AiService` is not `@Transactional`, on purpose.** A provider call must never happen
with a database transaction open — a slow model would pin a Hikari connection for the
length of its response. Read, commit, *then* call the provider.

**The explainer may rewrite prose and nothing else.** `TrainingInsightExplainer` receives
an already-computed `ExerciseInsight` and replaces only its text. Classifications, metrics
and evidence strings come from `TrainingAnalytics` and are never sent back through the
model. If you widen what the explainer returns, you break the invariant the whole
adaptive-training design rests on.

**The provider's timeout and semaphore are not decoration.** Virtual threads are not a
timeout and not a concurrency limit. `app.ai.timeout` sets both connect and read timeouts;
`app.ai.max-concurrent-requests` bounds in-flight calls and **rejects rather than queues**,
so a saturated provider sheds load instead of building a backlog.

**`login` must not be `@Transactional`.** It would hold a pooled connection across Argon2id
verification — ~100ms of CPU that touches no database — which monopolises the pool and makes
every other endpoint queue. Measured: read p95 −40% after removing it. The same rule already
applies to `AiService`.

**Failed emails must not retry without backoff.** A failed send is parked in
`fitness:email:retry` (a sorted set scored by due time), not re-added to the stream. With a
1s poll, immediate re-adds burn every attempt in seconds and defend against nothing.

**Dead-lettered messages are redacted.** Nothing drains `fitness:email:dead`, so the raw
one-time token is stripped before the message lands there. Keep it that way.

**Argon2 parameters are pinned deliberately** in `PasswordEncoderConfig` to match the
existing hash corpus (m=65536, t=3, p=4). Spring's defaults are weaker and would
silently downgrade every password changed after cutover.

**`TokenRevocationService` must stay a separate bean with `REQUIRES_NEW`.** Inlining it
makes refresh-token reuse detection report the attack and then roll back the revocation.
`RefreshTokenReuseTransactionSpikeTest` fails if anyone tries.

**Numbers must round like CPython.** `AnalyticsNumbers.round` uses `new BigDecimal(double)`
with `HALF_EVEN`; `Math.round` and `BigDecimal.valueOf` both disagree with CPython on ties
and would corrupt every percentage the analytics engine emits. Verified by
`src/test/resources/contract/training-insights-fixtures.json`, which pins the behaviour and
consumed by both test suites.

**Field names with digit runs need `@JsonProperty`.** Jackson renders
`workoutsLast7Days` as `workouts_last7_days`; the contract is `workouts_last_7_days`.

**JSON is snake_case and nulls are written.** The Next.js frontend reads `weight_kg`,
`performed_at` and friends directly. See `JacksonConfig`.

**Actuator tests need `@AutoConfigureObservability`.** `@SpringBootTest` applies
`DisableObservabilityContextCustomizer`, which sets
`management.defaults.metrics.export.enabled=false` — so `/actuator/prometheus` is
unmapped in tests while working perfectly in production. That cost an afternoon once;
`ActuatorEndpointsTest` carries the annotation and a comment saying why.

**`NaiveTokenRotation` is test-only and must stay that way.** It reproduces the
transaction bug that loses refresh-token revocation, purely so the regression test can
prove the correct implementation differs. It is not on the production classpath.

**Actuator is on its own port** (`management.server.port`, default 9000) which Compose
does not publish. Prometheus scrapes it from inside the Docker network.
`/api/v1/health` and `/api/v1/ready` stay on the application port — nginx, the Docker
healthcheck and the frontend use them.

**JWT claims are a contract, not an implementation detail.** No `iss`, no `aud`, a `type`
claim of `access`/`refresh`, and `jti` doubling as the `refresh_tokens` primary key. See
`docs/java-migration-plan.md` §8.7 and `contract/jwt-fixtures.json`.

**`SECRET_KEY` must be at least 32 bytes.** HS256 requires it (RFC 7518 §3.2); Nimbus
enforces it where PyJWT only warns. `TokenService` fails fast at startup with a message
saying so.

## Known limitations

- **The AI layer needs a provider key to do anything.** With `app.ai.api-key` unset,
  `/api/v1/ai/**` returns 503 and `?explain=true` falls back to deterministic prose with
  `explanation_source: "deterministic"`. No test ever calls the real
  provider — tests bind `FakeAiProvider`, and end-to-end validation used a local stub.
- **Actuator is on management port 9000, which is not published.** Prometheus scrapes it
  over the Compose network; nothing on the host can reach it. Formerly this was exposed on
  the application port, unauthenticated, reachable on 8001.
  Acceptable while the service is parity-test-only and unrouted; before any production
  traffic it needs either a separate management port or authentication.
- **The image build is slow from cold** (several minutes) because the Maven repository
  starts empty. The BuildKit cache mount makes rebuilds fast.
- **No load-shedding at the edge.** Overload is absorbed by the connection pool and the AI
  semaphore rather than being rejected at the front door.
