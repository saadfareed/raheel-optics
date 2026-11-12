#!/usr/bin/env bash
# Applies all migrations to a throwaway local PostgreSQL DB, then runs backend/tests/*.sql.
# Never touches Supabase. Needs a local server (PG* env vars) with createdb rights.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT/backend"
DB="raheel_gate_$$"
createdb "$DB"; trap 'dropdb --if-exists "$DB"' EXIT
P=(psql -v ON_ERROR_STOP=1 -q -d "$DB")
"${P[@]}" -f tests/bootstrap.sql
for f in $(ls migrations/*.sql | sort); do echo "migrate $f"; "${P[@]}" -f "$f"; done
fail=0
for t in tests/*.sql; do
  case "$t" in tests/bootstrap.sql|*fixture*|*projection-upgrade.sql) continue;; esac
  if "${P[@]}" -f "$t" >/tmp/gate-sql.out 2>&1; then echo "PASS $t"; else echo "FAIL $t"; tail -5 /tmp/gate-sql.out; fail=1; fi
done
# Only mail/ is collected: several tests/test_*.py import scripts that are not in this repo.
(cd mail && python3 -I -m pytest -q . 2>&1 | tail -3) || fail=1
[ $fail = 0 ] && echo "BACKEND GATE: PASS" || { echo "BACKEND GATE: FAIL"; exit 1; }
