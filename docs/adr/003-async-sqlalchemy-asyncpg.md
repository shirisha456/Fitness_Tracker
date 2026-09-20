# ADR 003 — Async SQLAlchemy 2.0 with the asyncpg driver

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [../architecture.md](../architecture.md).

**Superseded.** The backend now uses Spring Data JPA / Hibernate 6 over a HikariCP pool.

## Status
Accepted.

## Context
FastAPI is an async framework end-to-end; a synchronous DB driver would block the event loop on
every query, defeating the point of running async request handlers at all.

## Decision
Use SQLAlchemy 2.0's native async support (`create_async_engine`, `AsyncSession`,
`async_sessionmaker`) with the `asyncpg` driver, connection string
`postgresql+asyncpg://...`. Every route, service function, and repository call is `async def`
all the way down — there's no sync/async boundary anywhere in the request path.

## Consequences
- Every DB call in every service function must be awaited; there's no accidental sync fallback
  to fall back on.
- `asyncpg` doesn't support every `psycopg2`-era SQLAlchemy feature (notably some
  server-side-cursor patterns), but nothing in this codebase's query patterns needed them.
- Alembic migrations still run synchronously by default — `alembic/env.py` wraps the async
  engine in `asyncio.run(run_async_migrations())` specifically to bridge that gap, since
  Alembic's own migration runner isn't async-native.
