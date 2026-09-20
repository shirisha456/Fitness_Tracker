# Rollback: Java → Python

The Java backend (`api-java`) is the active backend. The Python backend (`api`) is retained
as a **rollback target and behavioural reference**. It is not deprecated code waiting to be
deleted — it is the fallback, and it is kept working.

## Why this is safe

Both services share **one database, one Redis and one `SECRET_KEY`**. That is deliberate:

- **No schema change is involved in either direction.** Flyway is baselined onto the
  Alembic-created schema, and neither service migrates on rollback.
- **A JWT minted by either service validates against the other.** Switching does not log
  anyone out, in either direction.
- Refresh tokens, sessions and background email all live in the same shared stores.

Rollback is therefore a routing change, not a migration.

## The switch

Two variables, which must move together — if only one moves, the browser and the
server-rendered pages talk to different backends:

| | Java (active) | Python (rollback) |
|---|---|---|
| `API_UPSTREAM` | `api-java:8000` | `api:8000` |
| `BACKEND_INTERNAL_URL` | `http://api-java:8000/api` | `http://api:8000/api` |

### Roll back to Python

```bash
API_UPSTREAM=api:8000 \
BACKEND_INTERNAL_URL=http://api:8000/api \
  docker compose up -d --force-recreate nginx frontend
```

### Return to Java

```bash
docker compose up -d --force-recreate nginx frontend
```

Java is the committed default, so returning needs no variables at all — unset them (or
restore `.env` to the shipped values) and recreate.

## Verify which backend is live

```bash
# Config actually loaded by nginx after envsubst:
docker compose exec nginx grep -A1 'upstream api_backend' /etc/nginx/conf.d/default.conf

# What the BFF calls:
docker compose exec frontend printenv BACKEND_INTERNAL_URL

# Response fingerprint: Spring Security sets headers FastAPI does not.
curl -sSD - -o /dev/null http://localhost/api/v1/health | grep -i 'x-frame-options\|x-xss-protection'
```

`X-Frame-Options: DENY` and `X-XSS-Protection: 0` present ⇒ **Java**. Absent ⇒ **Python**.

## Smoke test after switching either way

```bash
EMAIL="rollback-$(date +%s)@example.com"
curl -s -o /dev/null -w 'health   %{http_code}\n' http://localhost/api/v1/health
curl -s -o /dev/null -w 'ready    %{http_code}\n' http://localhost/api/v1/ready
curl -s -o /dev/null -w 'register %{http_code}\n' -X POST http://localhost/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"SecurePass123!\",\"password_confirm\":\"SecurePass123!\"}"
curl -s -o /dev/null -w 'login    %{http_code}\n' -X POST http://localhost/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"SecurePass123!\"}"
```

Expect `200 / 200 / 201 / 200` from either backend. An account created on one works on the
other, which is the property that makes rollback non-destructive.

## Known behavioural differences after rollback

Rolling back to Python re-introduces the defects fixed during the migration, because the
Python source was deliberately left unchanged:

- `GET /nutrition/summary` and `GET /auth/verify-email` with a missing required parameter
  return 400 on both — this one Python always got right; it was Java that returned 500 until
  it was fixed.
- Python has **no metrics endpoint**, so Prometheus and the Grafana dashboard go blank for
  the API while Python is serving. Infrastructure metrics are unaffected.
- Python's email path uses Celery (`worker`/`beat`), which is at-most-once with no retry.
  Java's Redis Streams worker is at-least-once with backoff and a dead-letter stream. Both
  read the same Redis instance but different keys, so in-flight email does not transfer.
- Java sends security headers Python does not (`X-Frame-Options`, `X-Content-Type-Options`,
  `Cache-Control`, `Pragma`). Rolling back removes them.

Full deviation list: [api-compatibility.md](api-compatibility.md).

## Do not

- **Do not delete the Python backend, `worker` or `beat`** without explicit sign-off. They
  are the rollback path.
- **Do not migrate the database "for Java".** There is nothing to migrate; a divergent schema
  is what would make rollback impossible.
