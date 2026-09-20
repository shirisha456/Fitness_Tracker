"""Verify that the Python backend accepts JWTs minted by the Java backend.

The reverse direction of JwtCompatibilityTest. Run after the Java suite, which writes
backend-java/target/java-jwt-fixtures.json:

    backend/.venv/bin/python contract/verify_java_jwts.py

Both backends write tokens the other must read during the side-by-side period, so
compatibility has to hold in both directions.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys
import uuid

SHARED_SECRET = "contract-test-shared-secret-key-0123456789abcdefghijklmnopqrstuv"
os.environ["SECRET_KEY"] = SHARED_SECRET
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://unused@/unused")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.core.security import TokenError, decode_token  # noqa: E402

FIXTURES = ROOT / "backend-java" / "target" / "java-jwt-fixtures.json"

checks: list[tuple[str, bool, str]] = []


def check(label: str, passed: bool, detail: str = "") -> None:
    checks.append((label, passed, detail))
    print(("  ok    " if passed else "  FAIL  ") + label + (f"  [{detail}]" if detail else ""))


def main() -> int:
    if not FIXTURES.exists():
        print(f"missing {FIXTURES} — run the Java suite first")
        return 1
    data = json.loads(FIXTURES.read_text())
    user_id = data["user_id"]

    # This is exactly what get_current_user_from_token does before the DB lookup.
    access = decode_token(data["access_token"], expected_type="access")
    check("python decodes the java access token", True, f"type={access['type']}")
    check("sub matches", access["sub"] == user_id, access["sub"])
    check("role claim present", access.get("role") == "user", str(access.get("role")))
    check("email_verified claim present", "email_verified" in access,
          str(access.get("email_verified")))
    check("jti is a uuid", _is_uuid(access.get("jti")), str(access.get("jti")))
    check("iat and exp are numeric dates",
          isinstance(access.get("iat"), int) and isinstance(access.get("exp"), int))
    check("no issuer claim (python sets none)", "iss" not in access)
    check("no audience claim (python sets none)", "aud" not in access)
    check("access ttl is 900s", access["exp"] - access["iat"] == 900,
          str(access["exp"] - access["iat"]))

    refresh = decode_token(data["refresh_token"], expected_type="refresh")
    check("python decodes the java refresh token", True, f"type={refresh['type']}")
    check("refresh jti matches the java-reported jti",
          refresh["jti"] == data["refresh_jti"], refresh["jti"])
    check("refresh ttl is 7 days", refresh["exp"] - refresh["iat"] == 7 * 24 * 3600,
          str(refresh["exp"] - refresh["iat"]))
    check("refresh carries no role claim", "role" not in refresh)

    # Type confusion must be rejected in both directions.
    check("python rejects the java access token as a refresh token",
          _rejects(data["access_token"], "refresh"))
    check("python rejects the java refresh token as an access token",
          _rejects(data["refresh_token"], "access"))

    passed = sum(1 for _, ok, _ in checks if ok)
    print(f"\n{passed}/{len(checks)} checks passed")
    return 0 if passed == len(checks) else 1


def _is_uuid(value: object) -> bool:
    try:
        uuid.UUID(str(value))
        return True
    except (ValueError, TypeError):
        return False


def _rejects(token: str, expected_type: str) -> bool:
    try:
        decode_token(token, expected_type=expected_type)
        return False
    except TokenError:
        return True


if __name__ == "__main__":
    sys.exit(main())
