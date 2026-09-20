# ADR 002 — BFF pattern: Next.js Route Handlers hold the tokens, not the browser

## Status
Accepted.

## Context
The frontend is a Next.js App Router app; the backend is a separate Spring Boot service. Something
has to hold the JWT access/refresh pair between requests, and that something is directly exposed
to XSS risk if it's client-accessible JavaScript state or `localStorage`.

## Decision
Next.js Route Handlers under `frontend/app/api/**` act as a backend-for-frontend: they're the
*only* code that ever calls the backend directly (via `BACKEND_INTERNAL_URL`, the Docker-internal
`http://api:8000/api`, never exposed to the browser). The JWTs live in **httpOnly**,
`SameSite=Lax` cookies set by these route handlers — client-side JavaScript can never read them.

`authedBackendFetch` (used by mutating routes) attaches the access token, and on a `401` performs
one silent refresh-and-retry, rotating the cookies on its own response. `serverReadWithAccessToken`
(used by Server Components rendering a page) is a read-only variant with no refresh attempt —
Server Components can't set cookies mid-render, so a refresh there would rotate the backend's
refresh token without the client ever receiving the new one, silently breaking the *next* real
refresh. Trading a rare stale-read edge case (bounded by the access token's 15-minute lifetime)
for correctness beats a subtly broken refresh chain.

## Consequences
- The access/refresh tokens are never present in browser-accessible storage or JS state at all —
  an XSS bug can't directly exfiltrate a session token.
- Every domain feature (workouts, meals, etc.) needs its own thin proxy route
  (`frontend/app/api/workouts/route.ts` and so on) rather than the browser calling the backend
  directly — more files, but a single consistent auth/refresh boundary.
- nginx's routing split (see [ADR 005](005-nginx-path-routing-split.md)) exists specifically to
  make this pattern require zero new nginx config per new BFF route.
