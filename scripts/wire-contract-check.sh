#!/usr/bin/env bash
# Replays the client's real RPC payloads (app/build/wire-fixtures.tsv, written by WireFixtureExportTest) against all
# migrations on a throwaway PostgreSQL cluster. Never touches Supabase. Usage: scripts/wire-contract-check.sh
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk}"
./gradlew --console=plain :app:testQaUnitTest --tests '*WireFixtureExportTest' -q >/dev/null
FIX="$ROOT/app/build/wire-fixtures.tsv"; [ -s "$FIX" ] || { echo "no fixtures"; exit 2; }
BIN=$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1); export PATH="$BIN:$PATH"
W=$(mktemp -d /tmp/wirecheck.XXXX); trap 'pg_ctl -D "$W/data" stop -m fast >/dev/null 2>&1 || true; rm -rf "$W"' EXIT
initdb -D "$W/data" -A trust -U wire >/dev/null; pg_ctl -D "$W/data" -o "-k $W -p 5588 -c listen_addresses=''" -l "$W/log" -w start >/dev/null
export PGHOST="$W" PGPORT=5588 PGUSER=wire; createdb wire; export PGDATABASE=wire
P=(psql -v ON_ERROR_STOP=1 -q)
"${P[@]}" -f supabase/tests/bootstrap.sql; for f in $(ls supabase/migrations/*.sql | sort); do "${P[@]}" -f "$f"; done
USER_ID=11111111-1111-1111-1111-111111111111; n=0
while IFS=$'\t' read -r rpc body; do
  n=$((n+1))
  out=$("${P[@]}" -At -c "set role authenticated" -c "select set_config('request.jwt.claim.sub','$USER_ID',false)" \
    -c "select public.$rpc(\$wire\$$body\$wire\$::jsonb)::text" 2>&1) || { echo "FAIL #$n $rpc: $out"; exit 1; }
  out=${out##*$'\n'}
  case "$out" in *'"accepted": true'*) echo "ok   #$n $rpc";; *) echo "REJECTED #$n $rpc: $out"; exit 1;; esac
done < "$FIX"
# Read the order back the way devices do.
feed=$("${P[@]}" -At -c "set role authenticated" -c "select set_config('request.jwt.claim.sub','$USER_ID',false)" -c "select public.optical_order_feed()::text")
feed=${feed##*$'\n'}
case "$feed" in *'"status": "COMPLETE"'*'"paidPaisa": 150000'*|*'"paidPaisa": 150000'*'"status": "COMPLETE"'*) echo "ok   feed returns the completed order with its payments";;
  *) echo "FEED MISMATCH: $feed"; exit 1;; esac
echo "WIRE CONTRACT: PASS ($n calls + feed)"
