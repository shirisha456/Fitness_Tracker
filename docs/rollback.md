# Rollback

Rollback means **moving the API back to the previously known-good Java image**. It is a
container-image change, not a change of implementation.

```
current api-java image  ──rollback──▶  previous api-java image
                    same database · same Redis · same schema
```

## What makes this safe

- **No schema change is involved.** Flyway migrations are additive and the previous image
  validates against the same schema (`ddl-auto=validate`). A release that requires a
  destructive migration is not rollback-safe and must be split into expand/contract steps
  before it ships.
- **Sessions survive.** Tokens are signed with `SECRET_KEY`, which does not change between
  releases, so rolling back does not log anyone out.
- **Queued email survives.** Messages live in the Redis Stream, not in the container. A
  message enqueued by the new image is consumed by the old one; unacked entries are
  reclaimed by `XAUTOCLAIM` on the next start.

## Procedure

Images are tagged per release. Pin the tag, do not rely on `:latest`.

```bash
# 1. Identify the currently deployed image and the one before it
docker compose images api-java
docker image ls fitness-tracker-api-java

# 2. Pin the previous tag and recreate just the API
API_JAVA_IMAGE=fitness-tracker-api-java:<previous-tag> \
  docker compose up -d --force-recreate --no-deps api-java

# 3. Wait for health, then verify
docker compose ps api-java
./scripts/smoke.sh          # or the checks below
```

The frontend, nginx, PostgreSQL and Redis are untouched — only `api-java` is recreated, so
the blast radius is one container.

## Verify after rolling back

```bash
API=http://localhost/api/v1
EMAIL="rollback-$(date +%s)@example.com"

curl -s -o /dev/null -w 'health    %{http_code}\n' $API/health
curl -s -o /dev/null -w 'ready     %{http_code}\n' $API/ready
curl -s -o /dev/null -w 'register  %{http_code}\n' -X POST $API/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"SecurePass123!\",\"password_confirm\":\"SecurePass123!\"}"
curl -s -o /dev/null -w 'login     %{http_code}\n' -X POST $API/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"SecurePass123!\"}"

# Confirm which build is actually serving
curl -s $API/health -D - -o /dev/null | grep -i x-frame-options   # Spring Security is present
docker compose exec api-java printenv | grep -i version
```

Expect `200 / 200 / 201 / 200`. An account created before the rollback must still log in
afterwards — that is the check that proves no schema or token incompatibility crept in.

## If rollback does not fix it

Rolling the image back only helps when the fault is in application code. If health stays
red after a rollback, the cause is downstream — check PostgreSQL connectivity
(`hikaricp_connections_pending` climbing), Redis, or disk — and treat the rollback as
having ruled the application out rather than as having failed.

## Release checklist that keeps rollback possible

- Migrations are additive within a release; drops and renames are deferred to a later one.
- No release changes `SECRET_KEY`.
- The previous image tag is retained in the registry, not pruned.
- Health and readiness are checked before the previous image is discarded.

---

## Historical note

Earlier in this project's life the backend was implemented in Python/FastAPI, and during
the rewrite rollback meant switching nginx between the two implementations. That model no
longer exists: the Python service is not part of the architecture and is not deployed. It
remains available in git history for reference — see the `pre-java-only-cleanup` tag and
the `legacy-python-backend` branch — but it is not a supported rollback target and no
operational procedure should depend on it.
