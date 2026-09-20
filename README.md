# Adaptive Fitness Platform

**Live at: [fitness-tracker.18-221-88-168.sslip.io](https://fitness-tracker.18-221-88-168.sslip.io)** — deployed
on an AWS EC2 instance behind nginx, with a real Let's Encrypt HTTPS certificate.

A **Java 21 / Spring Boot** fitness platform that tracks workouts, nutrition, progress and
profile data, and provides **explainable adaptive training feedback** based on your workout
history.

It analyses your logged sessions to classify each exercise as progressing, stalling or
declining, and shows the numbers behind every verdict rather than just asserting it. An AI
coach can rephrase those findings in plain language — it never computes them, so the
feedback is reproducible and works with no API key configured.

## Why Fitness Tracker

**The problem:** tracking fitness usually means juggling several single-purpose apps — one for
counting calories, another for logging sets and reps, a spreadsheet for weight over time — none
of which talk to each other. That means no tool actually knows your full picture: what you ate
today, what you lifted this week, and how your weight has trended, all at once. Any "coaching"
those apps offer is generic, because it isn't reasoning over your real logged history.

**Where existing apps fall short:**

| Category | Strong at | Missing |
|---|---|---|
| Calorie/food-logging apps | Food/calorie logging, large food databases | Workout tracking is an afterthought; no goal-aware progress view; AI features are usually paywalled |
| Workout-logging apps | Set/rep/weight history | No nutrition tracking at all |
| AI workout-plan generators | AI-generated workout plans | No nutrition logging, no manual meal tracking |
| Micronutrient trackers | Deep micronutrient detail | No workout logging |
| Health data aggregators | Pulling data in *from* other apps | Don't originate structured workout/meal logs themselves; no coaching |

**What Fitness Tracker does differently:** one data model across workouts, nutrition, and body
progress, and on top of it an adaptive training engine that actually *analyzes* that history
rather than just displaying it.

### Adaptive training insights

The engine reads your logged sessions and classifies each exercise as `progressing`,
`stable`, `possible_plateau`, `declining`, `insufficient_data` or `not_applicable` — using
documented, deterministic rules with named thresholds, not an LLM's opinion. Every verdict
shows its evidence:

```
Bench Press — Progressing

Based on:
• Top load moved from 40 kg to 45 kg across 3 sessions (+12.5%)
• Top load increased in 2 consecutive sessions
• Session volume changed by +12.5%
• Analysis window: 2026-08-27 to 2026-09-10
```

It also reports 7-day training volume against the previous 7 days, weekly consistency,
personal bests, and a conservative next-session suggestion that the user chooses whether to
apply — nothing is changed automatically.

**The LLM never decides whether you progressed.** It receives the already-computed result and
rewords it in a sentence. With no API key, or during an OpenAI outage, the insights are
identical and the explanation falls back to deterministic text. See
[docs/adaptive-training.md](docs/adaptive-training.md) and
[ADR 006](docs/adr/006-deterministic-analytics-before-llm.md).

This is not a medical tool. It reports training-behaviour metrics, not health assessments, and
when a workout note mentions pain or injury it withholds the training suggestion and points you
to a qualified professional.

## What building this taught me

- **Modular-by-domain backend architecture** (`auth`, `workouts`, `nutrition`, `progress`,
  `profile`, `ai`, each with the same `models`/`schemas`/`service`/`routes` shape) kept every
  feature addition isolated — new modules never risked breaking ones already shipped.
- **The BFF pattern** (Next.js Route Handlers hold the JWTs in httpOnly cookies; the browser
  never sees a token) trades a bit of request-hop complexity for meaningfully better session
  security than storing tokens in `localStorage`.
- **Third-party API dependencies need designed-for failure modes.** The AI coach degrades
  gracefully (503 on missing key/quota, 502 on other OpenAI errors) instead of taking the whole
  app down when an external dependency has a bad day.
- **Keeping decision logic out of the LLM is what makes it testable.** Computing trend
  classifications in pure functions rather than prompting for them means 53 unit tests can
  assert exact verdicts on exact inputs — including that 100 → 102 kg (exactly the 2%
  threshold) is `progressing` while 100 → 101.9 kg is `stable`. No assertion like that exists
  against a language model.
- **Real infrastructure validation catches what unit tests don't.** Several real issues (nginx
  route shadowing, a Compose port-override that silently no-op'd, a migration that raced its own
  auto-migrate-on-restart) only surfaced when actually running the full Docker Compose stack —
  not from the test suite alone.
- **Production deployment is its own skill set**, separate from writing the app: EC2
  provisioning, Let's Encrypt via certbot's webroot method, nginx path-based routing between two
  services, and — as this project's iteration history shows — real deployments involve real
  friction (a Google Fonts fetch timing out mid-build, git checkouts drifting from what's
  actually running) that only surface once something is genuinely live.

## Screenshots
<img width="1010" height="747" alt="image" src="https://github.com/user-attachments/assets/6a111d38-c7ee-4be7-8781-e46abb0d6adc" />


<img width="1638" height="657" alt="image" src="https://github.com/user-attachments/assets/63e5c09e-c3c2-4eb2-9b77-b1509ac89789" />

<img width="1559" height="530" alt="image" src="https://github.com/user-attachments/assets/d1956bb7-787f-40db-846f-4d85eab61c00" />

<img width="1143" height="768" alt="image" src="https://github.com/user-attachments/assets/b7e13992-2c80-4e25-84fd-98ee49ae7ea2" />


## Stack

**Backend**
- Java 21, Spring Boot 3.5
- Spring Security, JWT (access + rotating refresh), Argon2id
- Spring Data JPA / Hibernate 6, Flyway
- PostgreSQL 16
- Redis 7 — Redis Streams for the background email pipeline

**Frontend**
- Next.js (App Router), TypeScript, Tailwind CSS, shadcn/ui
- BFF pattern: server-side Route Handlers hold JWTs in httpOnly cookies

**Infrastructure and operations**
- Docker Compose, nginx reverse proxy, Let's Encrypt TLS
- Prometheus + Grafana (Micrometer/Actuator), 10-panel service dashboard
- GitHub Actions: lint, test and build for backend and frontend
- k6 load tests with a seeded dataset and recorded benchmark artifacts

## Architecture

```
                      Browser
                         │
                         ▼
                 ┌───────────────┐
                 │     nginx     │   :80 / :443, TLS
                 └───┬───────┬───┘
            /api/v1/*│       │ /  and  /api/*
                     │       ▼
                     │   ┌──────────────────┐
                     │   │  Next.js (BFF)   │  httpOnly cookie sessions,
                     │   │   App Router     │  server-side calls
                     │   └────────┬─────────┘
                     ▼            │
              ┌──────────────────────────────┐
              │   Java 21 / Spring Boot      │
              │  auth · workouts · nutrition │
              │  progress · profile          │
              │  training insights · AI      │
              └──┬───────────┬────────────┬──┘
                 ▼           ▼            ▼
         ┌────────────┐ ┌─────────┐ ┌──────────────┐
         │ PostgreSQL │ │  Redis  │ │ AI provider  │
         │  (Flyway)  │ │ Streams │ │ (optional)   │
         └────────────┘ └────┬────┘ └──────────────┘
                             ▼
                   ┌──────────────────┐
                   │  Email worker    │  in-process consumer:
                   │  retry · DLQ     │  XREADGROUP / XACK / XAUTOCLAIM
                   └──────────────────┘

  Actuator :9000 (unpublished) ──▶ Prometheus ──▶ Grafana
```

**Synchronous** — everything the user waits for: page loads through the BFF, API calls
through nginx, database reads and writes, and optional AI generation (bounded by an explicit
timeout and a concurrency limit that sheds load rather than queueing).

**Asynchronous** — transactional email. Registration and password reset put a message on a
Redis Stream and return immediately; an in-process worker consumes it, acknowledges only
after the send succeeds, retries with exponential backoff, and dead-letters poison messages.
A Redis outage degrades to "no email", never to "registration failed".

## Phase status

| # | Phase | Description | Status |
|---|-------|--------------|--------|
| 1 | Foundation & Auth | Users, JWT access/refresh tokens with rotation and reuse detection, Argon2 password hashing, email verification | complete |
| 2 | Workouts | Exercise library, workout CRUD, workout-exercise ownership validation | complete |
| 3 | Nutrition | Meals, water entries, daily nutrition summary | complete |
| 4 | Progress | Body measurements, goals | complete |
| 5 | Profile | Single-row-per-user profile, wired into `/auth/me` | complete |
| 6 | AI Coach | OpenAI-backed workout/meal generation and chat, with graceful degradation on API failures | complete |
| 7 | Frontend Foundation & Auth UI | Next.js BFF pattern, httpOnly cookie sessions, login/register/password-reset pages | complete |
| 8 | Frontend Workouts | Workout list/detail/create/edit pages, exercise picker | complete |
| 9 | Frontend Nutrition | Meal CRUD pages, water quick-add, daily summary | complete |
| 10 | Frontend Progress | Weight chart, measurement and goal forms | complete |
| 11 | Frontend Profile | Profile form with server-side pre-population | complete |
| 12 | Frontend AI Coach | Workout/meal generation UI, chat interface | complete |
| 13 | Frontend Dashboard | Cross-module summary cards, quick actions | complete |
| 14 | Docker & nginx | Full Compose stack, nginx path-based routing between BFF and API | complete |
| 15 | CI | GitHub Actions: lint, test, build for both backend and frontend | complete |
| 16 | Production deploy config | HTTPS via Let's Encrypt, production Compose override, deployment verification scripts | complete |
| 17 | Adaptive training engine | Deterministic trend/plateau analytics over workout history, evidence-carrying API, Training Insights UI, AI used only for explanation | complete |
| 18 | Java/Spring Boot backend | Backend reimplemented in Java 21 / Spring Boot and validated response-for-response before cutover | complete |

See [docs/phase1.md](docs/phase1.md) through [docs/phase16.md](docs/phase16.md) for what was
built in each phase and how it was validated. Case study: [docs/case-study.md](docs/case-study.md).

## Quick start

### 1. Clone and configure

```bash
git clone <repo-url> fitness-tracker
cd fitness-tracker
cp .env.example .env
```

Edit `.env` and set a secure `SECRET_KEY`.

### 2. Run with Docker Compose

```bash
docker compose up --build
```

Services:

| Service    | Description                                          |
|------------|------------------------------------------------------|
| nginx      | http://localhost (port 80)                           |
| api-java   | Spring Boot backend (also on :8001)                  |
| frontend   | Next.js app                                          |
| db         | PostgreSQL 16                                        |
| redis      | Redis 7 — Streams queue for background email         |
| prometheus | Metrics, http://127.0.0.1:9090                       |
| grafana    | Dashboards, http://127.0.0.1:3001                    |
| mailhog    | Captured mail, http://localhost:8025                 |

Background email runs **inside** `api-java` via Redis Streams — there is no separate worker
container. Two email types did not justify a second deployable.

### 3. Verify

- Frontend: http://localhost
- Health: http://localhost/api/v1/health
- Readiness: http://localhost/api/v1/ready
- API docs (dev): http://localhost/api/docs
- Mailhog UI: http://localhost:8025
- Grafana: http://127.0.0.1:3001 (admin/admin by default — set `GRAFANA_PASSWORD` before
  exposing it anywhere)

Confirm which backend is serving:

```bash
docker compose exec nginx grep -A1 'upstream api_backend' /etc/nginx/conf.d/default.conf
# server api-java:8000;  ->  Java is active
```

### Environment notes

| Context | `DATABASE_URL` host | `REDIS_URL` host |
|---------|---------------------|------------------|
| Docker Compose | `db` (forced in compose `environment:`) | `redis` |
| Local `./mvnw spring-boot:run` | `localhost` | `localhost` |

`CORS_ORIGINS` must be **comma-separated**, not JSON:
`CORS_ORIGINS=http://localhost:3000,http://localhost`

## Local Development

### Backend (Java — active)

```bash
cd backend-java
./mvnw verify                 # compiles and runs the full test suite (Testcontainers; needs Docker)
./mvnw spring-boot:run        # needs db + redis: docker compose up -d db redis
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Set `NEXT_PUBLIC_API_URL=http://localhost:8000/api` for direct API access during local dev.

## Project Structure

```
fitness-tracker/
├── backend-java/     # Spring Boot application — ACTIVE backend
├── monitoring/       # Prometheus config, Grafana provisioning and dashboards
├── perf/             # Load-test scripts, dataset seed, benchmark results
├── frontend/         # Next.js application
├── nginx/            # Reverse proxy config
├── .github/workflows # CI pipelines
├── docs/             # Architecture, API reference, ADRs, phase write-ups, case study
└── docker-compose.yml
```

## Architecture

- [docs/architecture.md](docs/architecture.md) — system diagrams and module layout
- [docs/rollback.md](docs/rollback.md) — rolling the API back to the previous release
- [docs/performance-analysis.md](docs/performance-analysis.md) — load-testing methodology and the measured bottleneck fix
- [docs/api-compatibility.md](docs/api-compatibility.md) — endpoint-by-endpoint contract and accepted deviations
- [docs/performance-analysis.md](docs/performance-analysis.md) — load-testing methodology and the measured bottleneck fix
- [docs/adaptive-training.md](docs/adaptive-training.md) — the adaptive training engine: rules, queries, edge cases, limitations
- [docs/api.md](docs/api.md) — API reference
- [docs/case-study.md](docs/case-study.md) — engineering case study
- [docs/adr/](docs/adr/) — architecture decision records

## Historical migration validation

The backend was originally implemented in Python/FastAPI. It was reimplemented in Java 21 /
Spring Boot, and during that rewrite the original served as a **behavioural oracle**: an
automated differ replayed the same requests against both services and compared status codes
and JSON bodies field by field, reaching **64/64 identical responses** across seven suites
before any traffic was switched.

That implementation is no longer part of the architecture and is not deployed. It remains in
git history — tag `pre-java-only-cleanup`, branch `legacy-python-backend` — for reference
only.

What survives in the codebase is the *evidence*, not the code: the compatibility fixtures at
`backend-java/src/test/resources/contract/` still pin exact-value rounding, the JWT claim
shape, Argon2id parameters and the database schema, and four Java tests still consume them.

See [docs/migration-validation.md](docs/migration-validation.md).

## License

Proprietary — Fitness Tracker
