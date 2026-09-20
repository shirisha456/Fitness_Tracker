# Performance analysis

## Summary

A real bottleneck was found, fixed, and the fix measured on a reproducible benchmark:
**`AuthService.login` held a database connection across Argon2id password verification.**
Removing that improved read latency under concurrent login load by ~40% at p95 and p99,
with identical throughput and zero errors on both sides.

Getting there took four invalid measurements first. Those are documented, because the
reasons they were invalid are the useful part.

## Environment

All numbers below were measured on one machine; none are extrapolated.

| | |
|---|---|
| Host | Darwin arm64, 10 CPUs, 16 GB |
| Docker | 10 CPUs, 7 GB |
| Load generator | k6 0.53.0, containerised, 4 CPUs |
| Backend | `api-java` (Spring Boot 3.5.3, Java 21, virtual threads) |
| Database | PostgreSQL 16, `max_connections=100`, shared with the Python stack |
| Dataset | 100 users, 10k workouts, 40k workout-exercises, 30k meals, 6k measurements, 100 profiles |
| Commit | `3111596` (working tree dirty) |
| Date | 2026-09-19 |

## The bottleneck

`AuthService.login` was annotated `@Transactional`. That acquires a pooled connection on
method entry and holds it until the method returns — and the method's most expensive step is
Argon2id verification at m=65536, t=3, p=4, which is roughly 100ms of CPU (~400ms of CPU
time across its 4 lanes) and touches no database at all.

A connection held for ~100ms instead of ~1ms is a hundredfold reduction in what that pool
slot can serve. With the default pool of 10, concurrent logins monopolise connections and
every other endpoint queues behind them.

Two related defects were found in the same place:

1. **`issueTokenPair`'s `@Transactional` was never taking effect.** `login` called it as
   `this.issueTokenPair(...)`, and a self-invocation does not pass through the Spring proxy.
   The write succeeded anyway via the repository's own transaction, which is precisely why it
   had gone unnoticed. The same trap was already documented for refresh-token reuse
   revocation.
2. **Pool exhaustion surfaced as HTTP 500.** Under saturation this produced 648 `500`s on
   `/auth/login` — "the service is broken" when the truth was "the service is at capacity".

## Evidence the pool was the constraint

From the first stress run (offered load ramping to 5,000 req/s), read from the server's own
metrics:

| Signal | Value |
|---|---|
| HikariCP threads pending | 3,285 |
| HikariCP active | 10 (= pool max) |
| Connection acquisition timeouts | 3,775 |
| Worst-case acquisition | 30.9 s |
| Read endpoints' server-side p95 | ~860 ms |

Those reads do about 1 ms of actual query work. Substantially all of the 860 ms was waiting
for a connection.

## The change

1. `login` is no longer `@Transactional`. The user lookup runs in the repository's own short
   read transaction, Argon2 runs holding nothing, and the token write opens its own
   transaction.
2. Token issuance moved to `TokenIssuanceService`, so the write is genuinely transactional
   even when called from `login` in the same bean.
3. Pool sized explicitly to 24 (`DB_POOL_SIZE`), chosen against `max_connections=100` shared
   with the Python `api`, `worker` and `beat` containers — not "as large as possible".
4. `connection-timeout` reduced to 3 s, and pool-acquisition failure mapped to **503**.

## Result

Benchmark: `perf/k6/login-contention.js` — a constant 8 logins/s alongside 200 reads/s for
90 s. The login rate sits deliberately below the CPU ceiling so that what is measured is
contention for connections rather than Argon2 throughput. Pool pinned to 10 on **both**
sides, so the only difference is the `@Transactional` annotation.

| Metric | BEFORE | AFTER | Change |
|---|---|---|---|
| READ p50 | 1.23 ms | 1.10 ms | −11.0% |
| **READ p95** | **4.03 ms** | **2.41 ms** | **−40.1%** |
| **READ p99** | **9.92 ms** | **6.06 ms** | **−38.9%** |
| LOGIN p50 | 344.78 ms | 333.79 ms | −3.2% |
| LOGIN p95 | 392.79 ms | 353.53 ms | −10.0% |
| LOGIN p99 | 438.83 ms | 367.46 ms | −16.3% |
| Throughput | 206.6 req/s | 206.6 req/s | — |
| Failures | 0.000% | 0.000% | — |

Throughput is unchanged because the benchmark is rate-controlled: the load offered is fixed,
so the improvement appears as latency, which is the honest place for it to appear.

Login itself improves modestly — it no longer contends with reads for the pool — but it
remains ~350 ms because Argon2 is meant to cost that. **That cost is not a bug and must not
be "optimised".** Lowering it means weakening password hashing.

## Four measurements that were not results

Reporting only the final table would hide the more instructive part.

| # | What it showed | Why it was invalid |
|---|---|---|
| 1 | 46.6% failures, `max` 1,211,407 ms | Harness: setup-minted tokens outlived the 15-minute TTL, and one request hung with no timeout, stretching a 4m45s profile to 25 minutes |
| 2 | 21.9% failures | Harness: called `/nutrition/summary` without its required `date`. This *did* expose a real server bug — 8,720 HTTP 500s where Python returns 400 |
| 3 | 1407 → 252 req/s "regression" | Not a regression. Three changes were bundled into one measurement, so nothing was attributable |
| 4 | Re-running the *original* config gave 86 req/s | The host had degraded across successive 5,000 req/s runs (46,694 TCP connection-refused). The 5,000 req/s regime is not reproducible on this hardware, so no before/after claim can rest on it |

Measurement 4 is the one that matters. It was run specifically to check whether the apparent
regression was real, and it showed the *unchanged* configuration performing worse than the
changed one — which means the stress-regime numbers were measuring the laptop, not the code.
Had that check been skipped, the obvious conclusion ("the pool change caused a 6x
regression") would have been confidently wrong.

The baseline profile, by contrast, reproduces cleanly:

| Run | Throughput | Failed | p50 | p95 |
|---|---|---|---|---|
| baseline | 197.61 req/s | 0.000% | 0.92 ms | 1.97 ms |
| baseline recheck | 197.45 req/s | 0.000% | 0.92 ms | 2.34 ms |

That is why the final comparison uses a load the machine sustains rather than the one that
produced the most dramatic-looking numbers.

## Not claimed

- **No production capacity claim.** Every figure is from one laptop running the load
  generator, both backends, Postgres, Redis, Prometheus and Grafana simultaneously.
- **No Java-vs-Python comparison.** The Python backend exposes no metrics endpoint, and both
  would be competing for the same cores. Any such number would be noise.
- **No claim that 24 is the right pool size in production.** It is defensible here; the right
  value depends on the real database's `max_connections` and the real core count.
- The 5,000 req/s stress figures are recorded in `perf/results/` for provenance but are
  **not** cited as results.

## Reproducing

```
docker compose exec -T db psql -U fitforge -d fitforge -f - < perf/seed.sql
./perf/run.sh java baseline                              # 20 -> 300 req/s ramp
K6_SCRIPT=login-contention.js ./perf/run.sh java login   # login vs read contention
STRESS=1 ./perf/run.sh java stress                       # saturation; not reproducible here
```

Each run writes `environment.txt` (commit, host, CPU, memory, k6 version), `run.log` and
`summary.json` into `perf/results/<label>/`.
