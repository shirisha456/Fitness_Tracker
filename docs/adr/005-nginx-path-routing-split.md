# ADR 005 — nginx routes on the `/v1/` segment, not per-resource

## Status
Accepted.

## Context
Two upstreams need to share port 80/443 behind nginx: the real backend (always
versioned, `/api/v1/...`) and the Next.js BFF's own route handlers (unversioned —
`/api/auth/*`, `/api/workouts`, `/api/exercises`, and so on). A naive approach would add one
nginx `location` block per BFF resource, which means every new frontend feature needs an nginx
change too.

## Decision
Split purely on the `/v1/` path segment:

```
location /api/v1/ { proxy_pass http://api_backend; }   # straight to the backend
location /api/     { proxy_pass http://frontend_app; }  # everything else → Next.js BFF
location /         { proxy_pass http://frontend_app; }  # pages
```

Because every real backend endpoint is versioned and every BFF route is deliberately not, this
one rule is sufficient for the entire API surface, present and future.

## Consequences
- Adding a new BFF proxy route (as happened in every frontend phase from 8 onward — workouts,
  meals, water entries, measurements, goals, profile, AI) never requires touching
  `nginx.conf` — confirmed directly in Phase 14's Docker validation, where 20+ BFF routes all
  routed correctly through this single rule.
- If a future backend endpoint were ever added *unversioned*, it would silently be routed to the
  frontend instead of the API — the convention depends on every backend route staying under
  `/v1/`, which is enforced by `api_v1_router` being mounted at that prefix in `app/main.py`,
  not by anything in nginx itself.
- `nginx.prod.conf` reuses the identical split, just behind TLS — the routing logic doesn't
  change between dev and prod, only the transport.
