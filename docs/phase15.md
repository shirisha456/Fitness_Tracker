# Phase 15 — CI

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


A single file: `.github/workflows/ci.yml`.

## What was built

Two jobs, both running on every push/PR to `main`:

- **`backend`** — spins up real Postgres 16 and Redis 7 service containers, then
  `ruff check .` and `pytest -v` against them.
- **`frontend`** — `npm ci`, `npm run lint`, `npm run build`.

No deploy job in this workflow — `docker-compose.prod.yml` and the AWS EC2 setup in Phase 16 are
applied manually, not from CI. Keeping deploy manual avoided the failure mode of a CI-triggered
deploy step silently never succeeding while the pipeline still shows green.

## Validation

YAML syntax validated. Every command this workflow runs — `ruff check .`, `pytest -v`,
`npm run lint`, `npm run build` — had already been proven to pass repeatedly against this exact
codebase across Phases 1–13's own validation, with matching environment variables. Since this
repo has no GitHub remote yet, an actual live Actions run isn't something I could trigger.
