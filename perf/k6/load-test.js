// Load profile for the Fitness Tracker API.
//
// WHY k6 AND NOT GATLING
// ---------------------
// Gatling is the more natural fit on paper: a JVM tool for a JVM service, with a Maven
// plugin and a typed DSL. It was not chosen, for one reason that matters more than any of
// that on this setup — the load generator and the service under test share a laptop.
// Gatling would put a second JVM, with its own heap and its own garbage collector, next to
// the one being measured, and JVM-on-JVM contention is exactly the kind of noise that makes
// a p99 number untrustworthy. k6's VUs are goroutines, its footprint per VU is far smaller,
// and it runs as its own container with a CPU limit.
//
// Two lesser reasons: the scenarios here are plain HTTP flows that the existing bash
// validation scripts already express in the same shape, and k6 reports p50/p95/p99 and
// per-endpoint breakdowns directly, with a machine-readable summary, so no post-processing
// step sits between the run and the recorded numbers.
//
// The trade-off being accepted: Gatling's reports are richer, and its DSL would let the
// scenarios share code with the JUnit suite. Neither outweighs measurement fidelity.

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://api-java:8000/api/v1";
const USERS = parseInt(__ENV.USERS || "100", 10);
const PASSWORD = "LoadTest123!";
// STRESS=1 pushes past the point of saturation to locate the actual limiting resource.
const STRESS = __ENV.STRESS === "1";

// Per-endpoint latency, so a regression can be attributed rather than just observed.
const t = {
  login: new Trend("ep_login", true),
  workouts: new Trend("ep_workouts_list", true),
  workoutDetail: new Trend("ep_workout_detail", true),
  nutrition: new Trend("ep_nutrition_summary", true),
  measurements: new Trend("ep_measurements", true),
  training: new Trend("ep_training_overview", true),
  insights: new Trend("ep_training_insights", true),
  profile: new Trend("ep_profile", true),
};
const errors = new Rate("business_errors");
const requests = new Counter("business_requests");

// Every request gets a hard timeout. Without one, the first run had a single request hang
// for 1,211 seconds — k6 waits for in-flight iterations at the end of a scenario, so a
// 4m45s profile took 25 minutes and the `max` statistic became meaningless. A timeout turns
// "hung forever" into a recorded failure, which is information rather than noise.
const REQUEST_TIMEOUT = "30s";

export const options = {
  // k6's default trend stats stop at p(95); without this, p(99) reports "n/a".
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  discardResponseBodies: false,
  scenarios: {
    // The dominant workload: authenticated reads. Arrival-rate (open model) rather than a
    // fixed VU count, because a closed model hides latency — if the server slows down, VUs
    // simply send fewer requests and the offered load quietly drops with it.
    browse: {
      executor: "ramping-arrival-rate",
      startRate: 20,
      timeUnit: "1s",
      preAllocatedVUs: STRESS ? 400 : 50,
      maxVUs: STRESS ? 3000 : 400,
      // The baseline profile peaks at 300 req/s. That turned out to saturate nothing —
      // Hikari pending stayed at 0, CPU peaked at 20%, every read endpoint's p99 was under
      // 4.2ms. A benchmark that never reaches a limit cannot identify a bottleneck, so
      // STRESS=1 keeps climbing until something does give.
      stages: STRESS
        ? [
            { target: 500, duration: "45s" },
            { target: 1000, duration: "45s" },
            { target: 2000, duration: "45s" },
            { target: 3500, duration: "45s" },
            { target: 5000, duration: "45s" },
            { target: 5000, duration: "45s" },
            { target: 0, duration: "15s" },
          ]
        : [
            { target: 50, duration: "30s" },
            { target: 150, duration: "60s" },
            { target: 150, duration: "60s" },
            { target: 300, duration: "60s" },
            { target: 300, duration: "60s" },
            { target: 0, duration: "15s" },
          ],
      exec: "browse",
    },
    // Login is deliberately a separate, low-rate scenario. Argon2id at m=65536,t=3,p=4 is
    // meant to take ~100ms of CPU; mixing it into the read mix at read volume would
    // saturate CPU with password hashing and measure nothing else.
    login: {
      executor: "constant-arrival-rate",
      rate: 3,
      timeUnit: "1s",
      duration: STRESS ? "285s" : "285s",
      preAllocatedVUs: 10,
      maxVUs: 60,
      exec: "login",
    },
  },
  // Thresholds are recorded, not aspirational, and they are omitted entirely under STRESS:
  // a stress run is meant to cross them, and a "failed" verdict there would be noise.
  thresholds: STRESS
    ? {}
    : {
        "http_req_failed{scenario:browse}": ["rate<0.01"],
        "http_req_duration{scenario:browse}": ["p(95)<1000", "p(99)<2000"],
      },
};

function creds(i) {
  return {
    email: `loadtest-${String((i % USERS) + 1).padStart(4, "0")}@example.com`,
    password: PASSWORD,
  };
}

function authenticate(i) {
  const res = http.post(`${BASE}/auth/login`, JSON.stringify(creds(i)), {
    headers: { "Content-Type": "application/json" },
    tags: { endpoint: "login" },
    timeout: REQUEST_TIMEOUT,
  });
  t.login.add(res.timings.duration);
  if (res.status !== 200) return null;
  try {
    return res.json().data.access_token;
  } catch (e) {
    return null;
  }
}

// A token per VU, refreshed when it expires.
//
// The first version minted 25 tokens in setup() and reused them for the whole run. Access
// tokens live 15 minutes, so on a long run they went stale mid-flight and the reads started
// returning 401 — 5,186 of them on /workouts alone, which reads as a server failure in the
// summary when it is really the harness expiring. Re-authenticating on every iteration is
// the other wrong answer: Argon2 at m=65536 would dominate the CPU and the read benchmark
// would become a password-hashing benchmark.
let vuToken = null;

function tokenForVu() {
  if (vuToken === null) {
    vuToken = authenticate(__VU);
  }
  return vuToken;
}

function invalidateVuToken() {
  vuToken = null;
}

export function setup() {
  const tokens = [];
  for (let i = 0; i < 5; i++) {
    const tok = authenticate(i);
    if (tok) tokens.push(tok);
  }
  if (tokens.length === 0) {
    throw new Error("setup failed: no user could log in — is the dataset seeded?");
  }
  // One request to discover a real exercise id, so the insights endpoint exercises the
  // analytics path rather than a 404.
  const res = http.get(`${BASE}/exercises`, {
    headers: { Authorization: `Bearer ${tokens[0]}` },
  });
  let exerciseId = null;
  try {
    exerciseId = res.json().data[0].id;
  } catch (e) {
    /* leave null; the insights step is skipped below */
  }
  return { tokens, exerciseId };
}

function get(path, token, trend, endpoint) {
  let res = http.get(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { endpoint },
    timeout: REQUEST_TIMEOUT,
  });
  // A 401 here means this VU's token aged out, not that the server failed. Re-authenticate
  // once and retry, so token expiry never shows up as an error rate.
  if (res.status === 401) {
    invalidateVuToken();
    const fresh = tokenForVu();
    if (fresh) {
      res = http.get(`${BASE}${path}`, {
        headers: { Authorization: `Bearer ${fresh}` },
        tags: { endpoint },
        timeout: REQUEST_TIMEOUT,
      });
    }
  }
  trend.add(res.timings.duration);
  requests.add(1);
  const ok = check(res, { [`${endpoint} 200`]: (r) => r.status === 200 });
  errors.add(!ok);
  return res;
}

export function browse(data) {
  const token = tokenForVu();
  if (!token) {
    return;
  }

  // Weighted to resemble actual use: the dashboard and the workout list are what people
  // open; insights and profile are occasional.
  const roll = Math.random();
  if (roll < 0.35) {
    const res = get("/workouts?limit=20", token, t.workouts, "workouts_list");
    // Follow through to a detail page a third of the time, as a real session would.
    if (res.status === 200 && Math.random() < 0.33) {
      try {
        const id = res.json().data[0].id;
        if (id) get(`/workouts/${id}`, token, t.workoutDetail, "workout_detail");
      } catch (e) {
        /* empty list */
      }
    }
  } else if (roll < 0.6) {
    // `date` is required. Omitting it produced 12,383 HTTP 400s in an earlier run — 21.9%
    // of all traffic — which is the load generator sending a malformed request, not the
    // server failing. Vary the day so the query is not served from one hot row set.
    const day = new Date(Date.now() - Math.floor(Math.random() * 90) * 86400000)
      .toISOString()
      .slice(0, 10);
    get(`/nutrition/summary?date=${day}`, token, t.nutrition, "nutrition_summary");
  } else if (roll < 0.75) {
    get("/training/overview", token, t.training, "training_overview");
  } else if (roll < 0.87) {
    get("/measurements?limit=30", token, t.measurements, "measurements");
  } else if (roll < 0.95 && data.exerciseId) {
    get(`/training/exercises/${data.exerciseId}/insights`, token, t.insights, "training_insights");
  } else {
    get("/profile", token, t.profile, "profile");
  }
  sleep(Math.random() * 0.3);
}

export function login() {
  const token = authenticate(Math.floor(Math.random() * USERS));
  check(token, { "login issued a token": (x) => x !== null });
}

export function handleSummary(data) {
  return {
    "/results/summary.json": JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function q(m, k) {
  const v = m && m.values ? m.values[k] : undefined;
  return v === undefined ? "n/a" : `${v.toFixed(2)}ms`;
}

function textSummary(data) {
  const m = data.metrics;
  const http_ = m.http_req_duration;
  const lines = [
    "",
    "================ RESULT ================",
    `requests           ${m.http_reqs ? m.http_reqs.values.count : 0}`,
    `throughput         ${m.http_reqs ? m.http_reqs.values.rate.toFixed(1) : 0} req/s`,
    `failed             ${m.http_req_failed ? (m.http_req_failed.values.rate * 100).toFixed(3) : "0"}%`,
    `p50 / p95 / p99    ${q(http_, "med")} / ${q(http_, "p(95)")} / ${q(http_, "p(99)")}`,
    `max                ${q(http_, "max")}`,
    "",
    "per endpoint (p95):",
  ];
  for (const [name, metric] of Object.entries(m)) {
    if (name.startsWith("ep_")) {
      lines.push(`  ${name.replace("ep_", "").padEnd(22)} ${q(metric, "p(95)")}  (n=${metric.values.count})`);
    }
  }
  lines.push("========================================", "");
  return lines.join("\n");
}
