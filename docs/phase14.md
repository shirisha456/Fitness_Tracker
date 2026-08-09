# Phase 14 — Docker & nginx

Full local containerization: both Dockerfiles, the entrypoint script, `docker-compose.yml`, and
`nginx/nginx.conf`. The first infrastructure phase after 13 phases of application code.

## What was built

- **`backend/Dockerfile`** — `python:3.12-slim`, `libpq5` for the `psycopg`-adjacent async
  Postgres driver, `pip install .` from `pyproject.toml`.
- **`backend/scripts/entrypoint.sh`** — `alembic upgrade head` then `exec uvicorn`, so every
  container start is self-migrating.
- **`frontend/Dockerfile`** — a three-stage build (`deps` → `builder` → `runner`) producing a
  Next.js **standalone** output, running as a non-root `nextjs` user.
- **`docker-compose.yml`** — 8 services: `db`, `redis`, `api`, `worker` (Celery), `beat` (Celery
  scheduler), `mailhog`, `frontend`, `nginx`. `api`/`worker`/`beat` all build from the same
  backend image with different commands.
- **`nginx/nginx.conf`** — routes `/api/v1/*` straight to FastAPI, everything else
  (`/api/*` and `/*`) to the Next.js BFF. See [ADR 005](adr/005-nginx-path-routing-split.md).

## Validation — real containers, not just `compose config`

Built all 4 images and brought up the full 8-service stack (via an uncommitted override
remapping host ports, so nothing collided with the user's other running projects). Then a full
production-topology browser test through the actual nginx entrypoint: landing page, register,
login — all routed browser → nginx → frontend BFF → Docker-internal `api:8000` → Postgres, the
real service-to-service path, not host networking. Confirmed the verification email was
genuinely delivered to Mailhog via the real Celery `worker` container and Redis broker — the
first time that path was exercised anywhere in this project, since every earlier dev-server
phase used `CELERY_TASK_ALWAYS_EAGER=true`, which runs tasks synchronously in-process and never
touches the real worker at all. Logged a workout through the BFF and confirmed it appeared on
the dashboard, also through nginx. Everything torn down afterward: containers, network, volume,
and all built images removed.

## An honest, unfixed rough edge

The backend build step transfers an unexpectedly large (~841MB) build context — not because
`backend/` itself is large, but because `frontend/node_modules` gets sent as build context for
the frontend image, since there's no `.dockerignore` anywhere in the repository. The build still
completes correctly; it's just slower than it needs to be. Left as a known issue rather than
fixed in this phase.
