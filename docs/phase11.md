# Phase 11 — Frontend Profile

> **Historical document.** This records work done when the backend was implemented in
> Python/FastAPI, or the rewrite from it. That implementation is no longer part of the
> architecture and is not deployed; it remains in git history only (tag
> `pre-java-only-cleanup`, branch `legacy-python-backend`). Kept for context, not as a
> description of how the system works today — see [architecture.md](architecture.md).


Single-page profile form with server-side pre-population.

## What was built

- **`ProfileForm`** — display name, DOB, sex (`Select`), height, fitness goal, activity level
  (`Select`); upsert semantics matching the backend.
- Profile page (`/profile`) — a Server Component that reads the current profile and passes it
  as `defaultValues`, so the form always opens pre-filled rather than empty-then-fetching.

## Validation

`npm run lint`/`npm run build` clean. Real end-to-end browser test: created a profile via a
direct BFF call, reloaded the page and confirmed every field — including both `Select` values,
read via their hidden native-`<select>` mirrors — correctly pre-populated from the server-side
read; confirmed `/auth/me.has_profile` flipped to `true`, validating the Phase 5 backend wiring
end-to-end from the browser. Also edited the display name **through the actual UI submit
button** (not a direct fetch) and confirmed it persisted — the first fully-UI-driven form submit
in the whole project that didn't need a JS-dispatched click fallback.
