# Phase 16 — Production deploy

AWS EC2 deployment: `docker-compose.prod.yml`, `nginx/nginx.prod.conf`, and two PowerShell
verification scripts, applied against a real EC2 instance with real HTTPS.

## What was built

- **`docker-compose.prod.yml`** — an override that removes host port publishing from `db`/
  `redis` entirely (defense in depth beyond the security group), swaps nginx to the prod config,
  and adds a `profiles: ["tools"]`-gated `certbot` service invoked manually for cert
  issuance/renewal rather than started by `up -d`.
- **`nginx/nginx.prod.conf`** — HTTP→HTTPS redirect (with an ACME-challenge passthrough that
  must stay plain HTTP for certbot's renewal checks to work), and an HTTPS server block with the
  same `/api/v1/` routing split as the dev config.
- **`run_all_tests.ps1`** / **`run_verify.ps1`** — scripts from an earlier debugging session used
  to smoke-test a live deployment (docker compose up, wait for healthy, curl the health/ready
  endpoints, run pytest).

## Real deployment, not just config

This wasn't a dry run: the EC2 instance was provisioned, Docker Compose brought the full stack up
on it, and a real Let's Encrypt certificate was issued via certbot's webroot method against
[sslip.io](https://sslip.io) — a free wildcard-DNS service that resolves
`<anything>.<IP>.sslip.io` back to a literal IP, used here since no custom domain was registered.
The hostname `fitness-tracker.18-221-88-168.sslip.io` in `nginx.prod.conf` is that real instance's
address — not a credential or secret, just a public IP embedded in a DNS name.

## Validation

Verified `docker compose -f docker-compose.yml -f docker-compose.prod.yml config` merges
correctly (`db`/`redis` have no `ports:` key, `nginx` publishes both `80` and `443` with the SSL
cert paths and certbot volumes mounted), then deployed for real: brought the stack up on the EC2
instance, issued the certificate, and confirmed HTTPS served the app end to end with no browser
warning.
