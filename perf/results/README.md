# Benchmark artifacts

Each run directory holds:

| File | Kept in git | Why |
|---|---|---|
| `summary.json` | yes | **The authoritative artifact.** Every metric — throughput, error rate, p50/p95/p99, per-endpoint trends — is in here. |
| `environment.txt` | yes | Commit, host, CPU, memory, k6 version. A latency number without these is not a result. |
| `run.log` | only for reproducible runs | k6's console output. |

## Reproducible runs — these are the results

| Run | What it is |
|---|---|
| `baseline-java` | 20 → 300 req/s ramp. 197.61 req/s, 0.000% errors. |
| `baseline-recheck` | The same profile re-run to prove reproducibility: 197.45 req/s, 0.000%. |
| `login-BEFORE` / `login-AFTER` | The controlled before/after for the transaction-boundary fix. Read p95 4.03 ms → 2.41 ms. |

## Rejected runs — `stress-*`

**These are not results and are never cited as such.** They are retained only as provenance
for why they were rejected.

The ~5,000 req/s profile is not reproducible on this hardware. Re-running the *unchanged*
configuration scored 86 req/s against an earlier 1,407 req/s, and the host emitted 46,694 TCP
"connection refused" errors — the kernel was refusing connections, so those runs measured the
laptop rather than the application.

Their `run.log` files are excluded from git: they run to 12 MB each and are almost entirely
the same repeated connection-error line. `summary.json` and `environment.txt` are kept, and
they contain every metric the logs would tell you.
