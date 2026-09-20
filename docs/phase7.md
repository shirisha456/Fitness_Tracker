# Phase 7 — Frontend Foundation & Auth UI

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Next.js 15 (App Router) scaffolding, Tailwind + shadcn/ui primitives, the backend-for-frontend
(BFF) auth pattern, and all five auth pages. The first frontend phase.

## What was built

- **Scaffolding** — `package.json`, Tailwind/PostCSS/shadcn config, root layout, global styles,
  the landing page.
- **shadcn/ui primitives** — `button`, `card`, `input`, `label`, `form`, `select`, `sheet`,
  `badge`, `chart` — unmodified library components, vendored in per shadcn convention (they're
  copied into the repo, not installed as an npm package).
- **The BFF auth pattern** (`lib/auth/*`) — httpOnly, `SameSite=Lax` cookies hold the JWTs
  server-side; the browser never sees the raw tokens. `authedBackendFetch` retries a request once
  via silent refresh on a 401; `serverReadWithAccessToken` is a read-only variant for Server
  Components (which can't set cookies, so it can't safely rotate a refresh token mid-render).
  See [ADR 002](adr/002-bff-pattern-httponly-cookies.md).
- **`middleware.ts`** — redirects unauthenticated visitors away from protected routes and
  authenticated visitors away from `/login`/`/register`.
- **App shell** (`AppShell`, `nav-items`) — desktop sidebar + mobile sheet-based nav, introduced
  here even though most of its nav links don't have pages yet (Next.js `<Link href>` doesn't
  require the target to exist at build time — only a real navigation would 404).
- Five auth pages: login, register, forgot-password, reset-password, verify-email.

## Validation

`npm run lint` and `npm run build` clean (all 12 routes). A real end-to-end browser test against
a live backend (Docker network-namespace isolated from the user's other running projects):
register → login → redirect. First encountered here and repeated in every later frontend phase:
the very first click on a route immediately after Next.js dev-server compiles it sometimes
doesn't register (a Fast-Refresh timing artifact, not a code defect) — a second click, or a
direct JS-dispatched click, always succeeds.
