"""Send identical requests to the Python and Java backends and diff the responses.

    backend/.venv/bin/python contract/compare_backends.py [--suite workouts]

Both services run side by side against one database, so the same request can be replayed
against each and the JSON compared field by field. This is the check that catches what
unit tests cannot: a renamed field, a changed status code, a differently shaped list
entry, a null that became an omission.

Volatile values (ids, timestamps, tokens, correlation ids) are normalised before
comparison — their *presence* and *type* are asserted, their values are not.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from typing import Any

PYTHON_BASE = "http://localhost/api/v1"
JAVA_BASE = "http://localhost:8001/api/v1"
PASSWORD = "SecurePass123!"

# Values that legitimately differ between two backends handling the same request.
VOLATILE_KEYS = {
    "id", "correlation_id", "access_token", "refresh_token",
    "created_at", "updated_at", "generated_on", "expires_in",
    # Each backend gets its own user so the two never share rows — that keeps list
    # comparisons meaningful, and makes the address a per-run value like an id.
    "email",
    # Foreign keys to rows each backend created independently. Their presence and type
    # are asserted; the values cannot match by construction.
    "workout_id", "exercise_id", "user_id",
}


class Backend:
    def __init__(self, name: str, base: str) -> None:
        self.name = name
        self.base = base
        self.token: str | None = None

    def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.base + path, method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers)
        try:
            with urllib.request.urlopen(request) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            return exc.code, (json.loads(raw) if raw else None)

    def login(self, email: str) -> None:
        status, payload = self.call("POST", "/auth/login",
                                    {"email": email, "password": PASSWORD})
        if status != 200:
            raise SystemExit(f"{self.name} login failed: {status} {payload}")
        self.token = payload["data"]["access_token"]


def normalise(value: Any, path: str = "") -> Any:
    """Replace volatile values with a type marker so structure is what gets compared."""
    if isinstance(value, dict):
        return {k: ("<" + type_name(v) + ">" if k in VOLATILE_KEYS else normalise(v, f"{path}.{k}"))
                for k, v in sorted(value.items())}
    if isinstance(value, list):
        return [normalise(item, f"{path}[]") for item in value]
    return value


def type_name(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "bool"
    if isinstance(value, (int, float)):
        return "number"
    return "str"


def diff(left: Any, right: Any, path: str = "") -> list[str]:
    if isinstance(left, dict) and isinstance(right, dict):
        problems = []
        for key in sorted(set(left) | set(right)):
            if key not in left:
                problems.append(f"{path}.{key}: missing in python, java has {right[key]!r}")
            elif key not in right:
                problems.append(f"{path}.{key}: missing in java, python has {left[key]!r}")
            else:
                problems.extend(diff(left[key], right[key], f"{path}.{key}"))
        return problems
    if isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return [f"{path}: length {len(left)} (python) vs {len(right)} (java)"]
        problems = []
        for index, (a, b) in enumerate(zip(left, right)):
            problems.extend(diff(a, b, f"{path}[{index}]"))
        return problems
    if left != right:
        return [f"{path}: python={left!r} java={right!r}"]
    return []


class Comparison:
    def __init__(self) -> None:
        self.passed = 0
        self.failed = 0

    def check(self, label: str, py: tuple[int, Any], jv: tuple[int, Any]) -> None:
        problems = []
        if py[0] != jv[0]:
            problems.append(f"status: python={py[0]} java={jv[0]}")
        problems.extend(diff(normalise(py[1]), normalise(jv[1])))
        if problems:
            self.failed += 1
            print(f"  FAIL  {label}")
            for problem in problems[:6]:
                print(f"          {problem}")
        else:
            self.passed += 1
            print(f"  ok    {label}  [{py[0]}]")

    def report(self) -> int:
        print(f"\n{self.passed} identical, {self.failed} differing")
        return 0 if self.failed == 0 else 1


def register(email: str) -> None:
    backend = Backend("python", PYTHON_BASE)
    backend.call("POST", "/auth/register",
                 {"email": email, "password": PASSWORD, "password_confirm": PASSWORD})


def suite_workouts(py: Backend, jv: Backend, cmp: Comparison) -> None:
    print("== exercise library ==")
    cmp.check("GET /exercises", py.call("GET", "/exercises"), jv.call("GET", "/exercises"))
    cmp.check("GET /exercises?category=cardio",
              py.call("GET", "/exercises?category=cardio"),
              jv.call("GET", "/exercises?category=cardio"))

    _, library = py.call("GET", "/exercises")
    bench = next(e["id"] for e in library["data"] if e["name"] == "Bench Press")
    squat = next(e["id"] for e in library["data"] if e["name"] == "Squat")

    print("\n== workout create / read / update / delete ==")
    today = date.today()
    payload = {
        "name": "Contract workout",
        "performed_at": (today - timedelta(days=3)).isoformat(),
        "notes": "comparison",
        "exercises": [
            {"exercise_id": bench, "sets": 3, "reps": 8, "weight_kg": 45},
            {"exercise_id": squat, "sets": 5, "reps": 5, "weight_kg": 100.5, "notes": "deep"},
        ],
    }
    py_created = py.call("POST", "/workouts", payload)
    jv_created = jv.call("POST", "/workouts", payload)
    cmp.check("POST /workouts", py_created, jv_created)

    py_id = py_created[1]["data"]["id"]
    jv_id = jv_created[1]["data"]["id"]
    cmp.check("GET /workouts/{id}",
              py.call("GET", f"/workouts/{py_id}"), jv.call("GET", f"/workouts/{jv_id}"))

    update = dict(payload, name="Renamed", notes=None,
                  exercises=[{"exercise_id": squat, "sets": 4, "reps": 6}])
    cmp.check("PUT /workouts/{id}",
              py.call("PUT", f"/workouts/{py_id}", update),
              jv.call("PUT", f"/workouts/{jv_id}", update))

    print("\n== list shapes ==")
    # Each backend sees only its own user's workouts, so the lists are comparable.
    cmp.check("GET /workouts", py.call("GET", "/workouts"), jv.call("GET", "/workouts"))
    date_from = (today - timedelta(days=7)).isoformat()
    cmp.check("GET /workouts?date_from&date_to",
              py.call("GET", f"/workouts?date_from={date_from}&date_to={today.isoformat()}"),
              jv.call("GET", f"/workouts?date_from={date_from}&date_to={today.isoformat()}"))

    print("\n== error cases ==")
    missing = str(uuid.uuid4())
    cmp.check("GET /workouts/{unknown} -> 404",
              py.call("GET", f"/workouts/{missing}"), jv.call("GET", f"/workouts/{missing}"))
    bad = dict(payload, exercises=[{"exercise_id": missing, "sets": 3, "reps": 8}])
    cmp.check("POST /workouts with unknown exercise -> 400",
              py.call("POST", "/workouts", bad), jv.call("POST", "/workouts", bad))
    invalid = dict(payload, name="", exercises=[
        {"exercise_id": bench, "sets": 99, "reps": 0}])
    py_invalid, jv_invalid = py.call("POST", "/workouts", invalid), jv.call("POST", "/workouts", invalid)
    # Validation *messages* differ by framework; the status and the error code must not.
    cmp.check("POST /workouts invalid -> 400 code",
              (py_invalid[0], {"code": py_invalid[1]["error"]["code"]}),
              (jv_invalid[0], {"code": jv_invalid[1]["error"]["code"]}))

    print("\n== delete ==")
    cmp.check("DELETE /workouts/{id} -> 204",
              py.call("DELETE", f"/workouts/{py_id}"), jv.call("DELETE", f"/workouts/{jv_id}"))




def suite_nutrition(py: Backend, jv: Backend, cmp: Comparison) -> None:
    today = date.today()
    day = (today - timedelta(days=2)).isoformat()
    other = (today - timedelta(days=1)).isoformat()

    print("== meals ==")
    meal = {"name": "Contract meal", "logged_at": day, "calories": 520,
            "protein_g": 40.5, "carbs_g": 45.0, "fat_g": 18.25, "notes": "cmp"}
    py_created = py.call("POST", "/meals", meal)
    jv_created = jv.call("POST", "/meals", meal)
    cmp.check("POST /meals", py_created, jv_created)

    py_id, jv_id = py_created[1]["data"]["id"], jv_created[1]["data"]["id"]
    cmp.check("GET /meals/{id}", py.call("GET", f"/meals/{py_id}"), jv.call("GET", f"/meals/{jv_id}"))

    minimal = {"name": "Only calories", "logged_at": day, "calories": 200}
    cmp.check("POST /meals without macros", py.call("POST", "/meals", minimal),
              jv.call("POST", "/meals", minimal))

    update = {"name": "Replaced", "logged_at": other, "calories": 600}
    cmp.check("PUT /meals/{id} (full replace nulls macros)",
              py.call("PUT", f"/meals/{py_id}", update), jv.call("PUT", f"/meals/{jv_id}", update))
    cmp.check("GET /meals", py.call("GET", "/meals"), jv.call("GET", "/meals"))
    cmp.check("GET /meals?date_from&date_to",
              py.call("GET", f"/meals?date_from={day}&date_to={day}"),
              jv.call("GET", f"/meals?date_from={day}&date_to={day}"))

    print("\n== water ==")
    water = {"logged_at": day, "amount_ml": 500}
    py_water = py.call("POST", "/water-entries", water)
    jv_water = jv.call("POST", "/water-entries", water)
    cmp.check("POST /water-entries", py_water, jv_water)
    cmp.check("GET /water-entries?date",
              py.call("GET", f"/water-entries?date={day}"),
              jv.call("GET", f"/water-entries?date={day}"))

    print("\n== summary ==")
    cmp.check("GET /nutrition/summary (populated day)",
              py.call("GET", f"/nutrition/summary?date={day}"),
              jv.call("GET", f"/nutrition/summary?date={day}"))
    cmp.check("GET /nutrition/summary (empty day)",
              py.call("GET", "/nutrition/summary?date=2020-01-01"),
              jv.call("GET", "/nutrition/summary?date=2020-01-01"))

    print("\n== errors and delete ==")
    missing = str(uuid.uuid4())
    cmp.check("GET /meals/{unknown} -> 404",
              py.call("GET", f"/meals/{missing}"), jv.call("GET", f"/meals/{missing}"))
    cmp.check("DELETE /water-entries/{id} -> 204",
              py.call("DELETE", f"/water-entries/{py_water[1]['data']['id']}"),
              jv.call("DELETE", f"/water-entries/{jv_water[1]['data']['id']}"))
    cmp.check("DELETE /meals/{id} -> 204",
              py.call("DELETE", f"/meals/{py_id}"), jv.call("DELETE", f"/meals/{jv_id}"))


def suite_progress(py: Backend, jv: Backend, cmp: Comparison) -> None:
    today = date.today()
    day = (today - timedelta(days=2)).isoformat()

    print("== measurements ==")
    measurement = {"recorded_at": day, "weight_kg": 82.5, "body_fat_pct": 18.5,
                   "waist_cm": 82.0, "notes": "cmp"}
    py_created = py.call("POST", "/measurements", measurement)
    jv_created = jv.call("POST", "/measurements", measurement)
    cmp.check("POST /measurements", py_created, jv_created)

    # The upsert: posting the same day again must update in place, still 201.
    again = {"recorded_at": day, "weight_kg": 81.0}
    cmp.check("POST /measurements same day (upsert, still 201)",
              py.call("POST", "/measurements", again), jv.call("POST", "/measurements", again))

    py_id, jv_id = py_created[1]["data"]["id"], jv_created[1]["data"]["id"]
    cmp.check("GET /measurements/{id}",
              py.call("GET", f"/measurements/{py_id}"), jv.call("GET", f"/measurements/{jv_id}"))
    cmp.check("GET /measurements", py.call("GET", "/measurements"), jv.call("GET", "/measurements"))

    print("\n== goals ==")
    goal = {"title": "Reach 78kg", "target_weight_kg": 78.0, "target_date": "2026-12-31"}
    py_goal = py.call("POST", "/goals", goal)
    jv_goal = jv.call("POST", "/goals", goal)
    cmp.check("POST /goals (defaults to active)", py_goal, jv_goal)

    update = {"title": "Reach 78kg", "status": "achieved"}
    cmp.check("PUT /goals/{id}",
              py.call("PUT", f"/goals/{py_goal[1]['data']['id']}", update),
              jv.call("PUT", f"/goals/{jv_goal[1]['data']['id']}", update))
    cmp.check("GET /goals", py.call("GET", "/goals"), jv.call("GET", "/goals"))

    print("\n== delete ==")
    cmp.check("DELETE /goals/{id} -> 204",
              py.call("DELETE", f"/goals/{py_goal[1]['data']['id']}"),
              jv.call("DELETE", f"/goals/{jv_goal[1]['data']['id']}"))
    cmp.check("DELETE /measurements/{id} -> 204",
              py.call("DELETE", f"/measurements/{py_id}"),
              jv.call("DELETE", f"/measurements/{jv_id}"))


def suite_profile(py: Backend, jv: Backend, cmp: Comparison) -> None:
    print("== profile ==")
    cmp.check("GET /profile before create -> 404",
              py.call("GET", "/profile"), jv.call("GET", "/profile"))

    profile = {"display_name": "Alex", "date_of_birth": "1995-04-12", "sex": "female",
               "height_cm": 168.5, "fitness_goal": "Build strength",
               "activity_level": "very_active"}
    cmp.check("PUT /profile (create)",
              py.call("PUT", "/profile", profile), jv.call("PUT", "/profile", profile))
    cmp.check("GET /profile", py.call("GET", "/profile"), jv.call("GET", "/profile"))

    # Full replace: omitted fields must null out in both.
    cmp.check("PUT /profile (replace nulls omitted fields)",
              py.call("PUT", "/profile", {"display_name": "Only name"}),
              jv.call("PUT", "/profile", {"display_name": "Only name"}))
    cmp.check("GET /auth/me has_profile",
              py.call("GET", "/auth/me"), jv.call("GET", "/auth/me"))



def suite_training(py: Backend, jv: Backend, cmp: Comparison) -> None:
    """Training insights over identical, deliberately-shaped histories.

    Each backend gets its own user, so the two see the same inputs and no shared rows.
    Dates are relative to UTC today, because both services derive "today" from UTC.
    """
    from datetime import datetime, timezone
    today = datetime.now(timezone.utc).date()

    _, library = py.call("GET", "/exercises")
    ids = {e["name"]: e["id"] for e in library["data"]}
    bench, squat, running = ids["Bench Press"], ids["Squat"], ids["Running"]

    def log(backend: Backend, exercise: str, days_ago: int, sets: int = 3, reps: int = 8,
            weight: float | None = None, notes: str | None = None) -> None:
        entry: dict = {"exercise_id": exercise, "sets": sets, "reps": reps}
        if weight is not None:
            entry["weight_kg"] = weight
        if notes is not None:
            entry["notes"] = notes
        backend.call("POST", "/workouts", {
            "name": "Session",
            "performed_at": (today - timedelta(days=days_ago)).isoformat(),
            "exercises": [entry],
        })

    def log_both(*args, **kwargs) -> None:
        log(py, *args, **kwargs)
        log(jv, *args, **kwargs)

    print("== progressing ==")
    for days_ago, weight in [(21, 40.0), (14, 42.5), (7, 45.0)]:
        log_both(bench, days_ago, weight=weight)
    cmp.check("GET /training/exercises/{bench}/insights (progressing)",
              py.call("GET", f"/training/exercises/{bench}/insights"),
              jv.call("GET", f"/training/exercises/{bench}/insights"))
    cmp.check("GET /training/exercises/{bench}/history",
              py.call("GET", f"/training/exercises/{bench}/history"),
              jv.call("GET", f"/training/exercises/{bench}/history"))
    cmp.check("GET /training/exercises/{bench}/history?limit=2",
              py.call("GET", f"/training/exercises/{bench}/history?limit=2"),
              jv.call("GET", f"/training/exercises/{bench}/history?limit=2"))

    print("\n== plateau ==")
    for days_ago in (28, 21, 14, 7):
        log_both(squat, days_ago, weight=60.0)
    cmp.check("GET /training/exercises/{squat}/insights (possible_plateau)",
              py.call("GET", f"/training/exercises/{squat}/insights"),
              jv.call("GET", f"/training/exercises/{squat}/insights"))

    print("\n== not_applicable ==")
    for days_ago in (21, 14, 7):
        log_both(running, days_ago, sets=1, reps=1)
    cmp.check("GET /training/exercises/{running}/insights (not_applicable)",
              py.call("GET", f"/training/exercises/{running}/insights"),
              jv.call("GET", f"/training/exercises/{running}/insights"))

    print("\n== aggregate endpoints ==")
    cmp.check("GET /training/overview",
              py.call("GET", "/training/overview"), jv.call("GET", "/training/overview"))
    cmp.check("GET /training/recommendations",
              py.call("GET", "/training/recommendations"),
              jv.call("GET", "/training/recommendations"))

    print("\n== explain=true keeps the analytics identical ==")
    # No API key is configured, so both must fall back to deterministic prose and 200.
    cmp.check("GET /training/exercises/{bench}/insights?explain=true",
              py.call("GET", f"/training/exercises/{bench}/insights?explain=true"),
              jv.call("GET", f"/training/exercises/{bench}/insights?explain=true"))

    print("\n== errors ==")
    missing = str(uuid.uuid4())
    cmp.check("GET /training/exercises/{unknown}/insights -> 404",
              py.call("GET", f"/training/exercises/{missing}/insights"),
              jv.call("GET", f"/training/exercises/{missing}/insights"))



def suite_ai(py: Backend, jv: Backend, cmp: Comparison) -> None:
    """The AI coach, against a deterministic stub shared by both backends.

    The stub returns fixed content, so identical inputs produce identical model output and
    any difference in the response is the backend's, not the model's. No paid call is ever
    made from a test or a validation run.
    """
    from datetime import datetime, timezone
    today = datetime.now(timezone.utc).date()

    _, library = py.call("GET", "/exercises")
    squat = next(e["id"] for e in library["data"] if e["name"] == "Squat")

    print("== generation and chat ==")
    workout_request = {"goal": "build muscle", "duration_minutes": 30,
                       "difficulty": "beginner"}
    cmp.check("POST /ai/generate-workout",
              py.call("POST", "/ai/generate-workout", workout_request),
              jv.call("POST", "/ai/generate-workout", workout_request))

    meal_request = {"meal_type": "lunch", "target_calories": 500}
    cmp.check("POST /ai/generate-meals",
              py.call("POST", "/ai/generate-meals", meal_request),
              jv.call("POST", "/ai/generate-meals", meal_request))

    chat_request = {"messages": [{"role": "user", "content": "Any tips for today?"}]}
    cmp.check("POST /ai/chat",
              py.call("POST", "/ai/chat", chat_request),
              jv.call("POST", "/ai/chat", chat_request))

    print("\n== recommendations are grounded in computed signals ==")
    def log(backend: Backend, days_ago: int) -> None:
        backend.call("POST", "/workouts", {
            "name": "Session",
            "performed_at": (today - timedelta(days=days_ago)).isoformat(),
            "exercises": [{"exercise_id": squat, "sets": 3, "reps": 8, "weight_kg": 60.0}],
        })
    for days_ago in (28, 21, 14, 7):
        log(py, days_ago)
        log(jv, days_ago)

    cmp.check("GET /ai/recommendations",
              py.call("GET", "/ai/recommendations"), jv.call("GET", "/ai/recommendations"))

    print("\n== the explanation layer reworks prose, not facts ==")
    py_plain = py.call("GET", f"/training/exercises/{squat}/insights")
    jv_plain = jv.call("GET", f"/training/exercises/{squat}/insights")
    py_explained = py.call("GET", f"/training/exercises/{squat}/insights?explain=true")
    jv_explained = jv.call("GET", f"/training/exercises/{squat}/insights?explain=true")

    cmp.check("GET /training/.../insights?explain=true",
              py_explained, jv_explained)

    # The invariant under test is "explain=true reworks prose and nothing else". It holds
    # whether or not a provider is configured, so the expected explanation_source is not
    # hard-coded: with no key both backends legitimately answer "deterministic", and
    # asserting "ai" here would fail the run for a reason that has nothing to do with
    # parity. What must match is that the two backends agree, and that neither one let the
    # model touch a computed field.
    COMPUTED_FIELDS = (
        "classification", "metric_basis", "sessions_analyzed", "date_range",
        "current_weight_kg", "previous_weight_kg", "primary_change_percent",
        "volume_change_percent", "plateau_detected", "evidence", "suggestion")

    def explain_shape(plain, explained):
        changed = [f for f in COMPUTED_FIELDS
                   if plain[1]["data"][f] != explained[1]["data"][f]]
        return {"changed_computed_fields": changed,
                "explanation_source": explained[1]["data"]["explanation_source"]}

    py_shape = explain_shape(py_plain, py_explained)
    jv_shape = explain_shape(jv_plain, jv_explained)

    # 1. The two backends behave identically.
    cmp.check("explain=true behaves identically on both backends",
              (200, py_shape), (200, jv_shape))

    # 2. Neither backend let the explanation change a computed value. Compared against a
    #    literal so this fails loudly even if both backends were wrong in the same way.
    for label, shape in (("python", py_shape), ("java", jv_shape)):
        cmp.check(f"{label}: the model changed no computed field "
                  f"(source={shape['explanation_source']})",
                  (200, {"changed_computed_fields": []}),
                  (200, {"changed_computed_fields": shape["changed_computed_fields"]}))
        assert shape["explanation_source"] in ("ai", "deterministic"), (
            f"{label} returned an unknown explanation_source: "
            f"{shape['explanation_source']!r}")

    print("\n== validation and errors ==")
    cmp.check("POST /ai/chat with no messages -> 400",
              (py.call("POST", "/ai/chat", {"messages": []})[0], None),
              (jv.call("POST", "/ai/chat", {"messages": []})[0], None))

def suite_malformed(py: Backend, jv: Backend, cmp: Comparison) -> None:
    """Requests that are wrong, rather than requests that are right.

    Every other suite compares well-formed traffic, which is why a missing required query
    parameter returned 500 on Java and 400 on Python for the entire migration without being
    noticed — the Phase 13 load test found it, not these comparisons. Malformed input is the
    cheapest request a broken client or a scanner can send, and it should never reach the
    generic 500 path.
    """
    print("== required query parameters that are absent ==")
    cmp.check("GET /nutrition/summary with no date",
              py.call("GET", "/nutrition/summary"), jv.call("GET", "/nutrition/summary"))
    cmp.check("GET /auth/verify-email with no token",
              py.call("GET", "/auth/verify-email"), jv.call("GET", "/auth/verify-email"))

    print("\n== parameters of the wrong type ==")

    def without_prose(result):
        """Strip details[].message, which is an ACCEPTED deviation.

        Pydantic writes type errors like "Input should be a valid UUID, invalid character:
        found `n` at 1". Reproducing that prose in Java would mean reimplementing Pydantic's
        error strings per type, for text that is displayed, not branched on. The
        machine-readable parts — status, error.code and details[].code — are compared
        normally, and the message is checked below for the properties that actually matter.
        """
        status, body = result
        if not isinstance(body, dict) or "error" not in body:
            return result
        copy = json.loads(json.dumps(body))
        for detail in copy.get("error", {}).get("details", []) or []:
            detail.pop("message", None)
        return status, copy

    for path in ("/nutrition/summary?date=not-a-date", "/workouts/not-a-uuid"):
        py_res, jv_res = py.call("GET", path), jv.call("GET", path)
        cmp.check(f"GET {path} (ignoring prose)",
                  without_prose(py_res), without_prose(jv_res))
        # The prose is not compared, but it still has to be present, human-readable and free
        # of internals. An empty or stack-trace-bearing message would otherwise pass above.
        message = ""
        try:
            message = jv_res[1]["error"]["details"][0]["message"]
        except Exception:
            pass
        leaky = any(tok in message for tok in
                    ("com.fitnesstracker", "org.springframework", "Exception", "\tat "))
        cmp.check(f"GET {path} java message is present and leaks nothing",
                  (jv_res[0], {"message_non_empty": True, "leaks_internals": False}),
                  (jv_res[0], {"message_non_empty": bool(message), "leaks_internals": leaky}))

    cmp.check("GET /workouts?limit=abc",
              py.call("GET", "/workouts?limit=abc"), jv.call("GET", "/workouts?limit=abc"))

    print("\n== unknown routes ==")
    cmp.check("GET /does-not-exist",
              py.call("GET", "/does-not-exist"), jv.call("GET", "/does-not-exist"))


SUITES = {
    "malformed": suite_malformed,
    "workouts": suite_workouts,
    "ai": suite_ai,
    "training": suite_training,
    "nutrition": suite_nutrition,
    "progress": suite_progress,
    "profile": suite_profile,
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", default="workouts",
                        choices=sorted(SUITES) + ["all"])
    args = parser.parse_args()

    stamp = uuid.uuid4().hex[:8]
    # A user per backend: identical inputs, no shared rows to confuse the list comparison.
    py_email, jv_email = f"cmp-py-{stamp}@example.com", f"cmp-jv-{stamp}@example.com"
    register(py_email)
    register(jv_email)

    py, jv = Backend("python", PYTHON_BASE), Backend("java", JAVA_BASE)
    py.login(py_email)
    jv.login(jv_email)

    suites = sorted(SUITES) if args.suite == "all" else [args.suite]
    print(f"comparing {', '.join(suites)}: python={PYTHON_BASE} java={JAVA_BASE}\n")
    cmp = Comparison()
    for name in suites:
        print(f"--- {name} ---")
        SUITES[name](py, jv, cmp)
        print()
    return cmp.report()


if __name__ == "__main__":
    sys.exit(main())
