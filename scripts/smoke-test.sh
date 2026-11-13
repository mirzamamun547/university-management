#!/usr/bin/env bash
# Smoke test for a running stack (e.g. after `docker compose up -d`).
#
#   bash scripts/smoke-test.sh                       # http://localhost:8080, admin/admin123
#   BASE_URL=http://localhost:8081 bash scripts/smoke-test.sh
#
# Exits non-zero (and prints the response body) on the first check that fails.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USERNAME:-admin}"
ADMIN_PASS="${ADMIN_PASSWORD:-admin123}"
JSON='Content-Type: application/json'

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
SUFFIX="$(date +%s)"          # unique names, so the script can be re-run against the same database

fail() { echo "FAIL: $1" >&2; exit 1; }

# expect <status> <description> <curl arguments...>
expect() {
  local want="$1" what="$2" got
  shift 2
  got="$(curl -s -o "$TMP/body" -w '%{http_code}' "$@")" || fail "$what (curl could not connect)"
  if [ "$got" != "$want" ]; then
    echo "response body: $(cat "$TMP/body")" >&2
    fail "$what: expected HTTP $want but got $got"
  fi
  echo "PASS: $what ($got)"
}

echo "Waiting for $BASE_URL (up to 2 minutes) ..."
for i in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/teachers" || true)"
  [ "$code" = "401" ] && break          # 401 = app is up and security is active
  [ "$i" = "60" ] && fail "app did not become ready (last status: $code)"
  sleep 2
done

# --- public page, closed API, authentication ---------------------------------------------
expect 200 "web UI is public"                      "$BASE_URL/"
expect 401 "API needs a login"                     "$BASE_URL/api/teachers"
expect 401 "wrong password is rejected"            -X POST -H "$JSON" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"wrong-password\"}" "$BASE_URL/api/auth/login"
expect 200 "admin logs in"                         -c "$TMP/admin.jar" -X POST -H "$JSON" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE_URL/api/auth/login"
expect 200 "session cookie identifies the admin"   -b "$TMP/admin.jar" "$BASE_URL/api/auth/me"

# --- admin manages teachers --------------------------------------------------------------
T_USER="teacher$SUFFIX"
expect 201 "admin creates a teacher"               -b "$TMP/admin.jar" -X POST -H "$JSON" \
  -d "{\"username\":\"$T_USER\",\"password\":\"secret123\",\"fullName\":\"Smoke Teacher\",\"email\":\"$T_USER@uni.edu\",\"department\":\"CSE\"}" \
  "$BASE_URL/api/teachers"
T_ID="$(sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$TMP/body")"
[ -n "$T_ID" ] || fail "could not read the id of the new teacher from: $(cat "$TMP/body")"

# --- the teacher is limited to his own profile -------------------------------------------
expect 200 "teacher logs in"                       -c "$TMP/teacher.jar" -X POST -H "$JSON" \
  -d "{\"username\":\"$T_USER\",\"password\":\"secret123\"}" "$BASE_URL/api/auth/login"
expect 200 "teacher reads his own profile"         -b "$TMP/teacher.jar" "$BASE_URL/api/teachers/$T_ID"
expect 403 "teacher cannot list teachers"          -b "$TMP/teacher.jar" "$BASE_URL/api/teachers"
expect 403 "teacher cannot list students"          -b "$TMP/teacher.jar" "$BASE_URL/api/students"
expect 403 "teacher cannot use the admin API"      -b "$TMP/teacher.jar" "$BASE_URL/api/admins"

# logout without -c, so the old cookie stays in the jar and we prove the SERVER ended the session
expect 200 "teacher logs out"                      -b "$TMP/teacher.jar" -X POST "$BASE_URL/api/auth/logout"
expect 401 "old session cookie no longer works"    -b "$TMP/teacher.jar" "$BASE_URL/api/auth/me"

# --- clean up ----------------------------------------------------------------------------
expect 204 "admin deletes the teacher"             -b "$TMP/admin.jar" -X DELETE "$BASE_URL/api/teachers/$T_ID"
expect 404 "deleted teacher is gone"               -b "$TMP/admin.jar" "$BASE_URL/api/teachers/$T_ID"

echo "All smoke checks passed."
