// Does login starve everything else of database connections?
//
// The Phase 13 stress run saturated the pool (3,285 threads pending, 3,775 acquisition
// timeouts, 30.9s worst-case acquire), but it did so at an offered load that also exhausts
// the host — connection-refused at the socket, numbers that do not reproduce between runs.
// An unreproducible benchmark cannot support a before/after claim.
//
// This one isolates the mechanism instead, at a load the machine sustains comfortably.
//
// The defect: AuthService.login was @Transactional, so a pooled connection was acquired
// before the password check and held across Argon2id verification — ~100ms of pure CPU that
// touches no database. A connection held for 100ms instead of ~1ms is a 100x reduction in
// what that pool slot can serve.
//
// The prediction, which is what makes this a test rather than a demo:
//   - connection HOLD time (hikaricp_connections_usage) collapses from ~Argon2 duration to
//     ~query duration.
//   - read latency under concurrent login load improves, because reads stop queueing behind
//     logins that are sitting on connections doing arithmetic.
//
// Login rate is set so that, BEFORE the fix, logins alone demand more than the pool can
// supply: 40/s x ~100ms hold = ~4 connections permanently occupied out of 10, rising sharply
// with any burst. Reads then contend for what is left.

import http from "k6/http";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://api-java:8000/api/v1";
const USERS = parseInt(__ENV.USERS || "100", 10);
const PASSWORD = "LoadTest123!";
const TIMEOUT = "30s";
// Argon2id at m=65536,t=3,p=4 costs roughly 400ms of CPU per login. At 40 logins/s that is
// ~16 CPU-seconds per wall-second on a 10-core host — CPU-bound before the pool is ever the
// constraint, and the first attempt at this benchmark duly collapsed (login p50 13s). The
// rate is set below the CPU ceiling so that what it measures is CONTENTION FOR CONNECTIONS,
// which is the thing the fix changes.
const LOGIN_RATE = parseInt(__ENV.LOGIN_RATE || "8", 10);
const READ_RATE = parseInt(__ENV.READ_RATE || "200", 10);

const loginTrend = new Trend("ep_login", true);
const readTrend = new Trend("ep_read", true);

export const options = {
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  scenarios: {
    logins: {
      executor: "constant-arrival-rate",
      rate: LOGIN_RATE,
      timeUnit: "1s",
      duration: "90s",
      preAllocatedVUs: 30,
      maxVUs: 200,
      exec: "login",
    },
    reads: {
      executor: "constant-arrival-rate",
      rate: READ_RATE,
      timeUnit: "1s",
      duration: "90s",
      preAllocatedVUs: 60,
      maxVUs: 300,
      exec: "read",
    },
  },
};

function creds(i) {
  return {
    email: `loadtest-${String((i % USERS) + 1).padStart(4, "0")}@example.com`,
    password: PASSWORD,
  };
}

export function setup() {
  const res = http.post(`${BASE}/auth/login`, JSON.stringify(creds(0)), {
    headers: { "Content-Type": "application/json" },
    timeout: TIMEOUT,
  });
  if (res.status !== 200) {
    throw new Error(`setup login failed: ${res.status}`);
  }
  return { token: res.json().data.access_token };
}

export function login() {
  const res = http.post(
    `${BASE}/auth/login`,
    JSON.stringify(creds(Math.floor(Math.random() * USERS))),
    { headers: { "Content-Type": "application/json" }, tags: { endpoint: "login" }, timeout: TIMEOUT }
  );
  loginTrend.add(res.timings.duration);
}

// A deliberately cheap read. Any latency it shows is contention, not its own work.
export function read(data) {
  const res = http.get(`${BASE}/workouts?limit=20`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { endpoint: "read" },
    timeout: TIMEOUT,
  });
  readTrend.add(res.timings.duration);
}

export function handleSummary(d) {
  const m = d.metrics;
  const f = (x, k) => (m[x] && m[x].values[k] !== undefined ? m[x].values[k].toFixed(2) : "n/a");
  return {
    "/results/summary.json": JSON.stringify(d, null, 2),
    stdout:
      "\n================ RESULT ================\n" +
      `requests        ${m.http_reqs ? m.http_reqs.values.count : 0}\n` +
      `throughput      ${m.http_reqs ? m.http_reqs.values.rate.toFixed(1) : 0} req/s\n` +
      `failed          ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(3) : 0}%\n` +
      `LOGIN  p50/p95/p99   ${f("ep_login", "med")} / ${f("ep_login", "p(95)")} / ${f("ep_login", "p(99)")} ms\n` +
      `READ   p50/p95/p99   ${f("ep_read", "med")} / ${f("ep_read", "p(95)")} / ${f("ep_read", "p(99)")} ms\n` +
      "========================================\n",
  };
}
