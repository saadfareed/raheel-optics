#!/usr/bin/env bash
# Applies named migrations to the live Supabase database, e.g.  scripts/apply-migrations.sh 020 021 022 023
# Needs DATABASE_URL (Supabase > Project Settings > Database > Connection string, session pooler or direct) in the
# environment or in the git-ignored .env. Backs up the public schema first (Supabase-managed auth/storage schemas stall pg_dump through the pooler), applies each file once (recorded in
# public.raheel_migrations), stops at the first error, then verifies the order API.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"; cd "$ROOT"
[ $# -gt 0 ] || { echo "Name the migrations to apply, e.g. $0 020 021 022 023"; exit 2; }
if [ -z "${DATABASE_URL:-}" ] && [ -f .env ]; then DATABASE_URL=$(grep -E '^DATABASE_URL=' .env | head -1 | cut -d= -f2- | sed -e 's/^["'"'"']//' -e 's/["'"'"']$//'); fi
[ -n "${DATABASE_URL:-}" ] || { echo "DATABASE_URL is not set (environment or .env)."; exit 2; }
BIN=$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | sort -V | tail -1); [ -n "$BIN" ] && export PATH="$BIN:$PATH"
P=(psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -q)
echo "Target: $(printf '%s' "$DATABASE_URL" | sed -E 's#//([^:@/]+)(:[^@]*)?@#//\1:***@#')"
"${P[@]}" -Atc "select 'Connected as '||current_user||' to '||current_database()||' (PostgreSQL '||current_setting('server_version')||')'"
"${P[@]}" -c "create table if not exists public.raheel_migrations(name text primary key, applied_at timestamptz not null default now());
  revoke all on public.raheel_migrations from anon, authenticated;"

mkdir -p build/backups; BACKUP="build/backups/before-$(date +%Y%m%d-%H%M%S).dump"
echo "Backing up to $BACKUP ..."; pg_dump "$DATABASE_URL" --schema=public -Fc --no-owner --no-privileges -f "$BACKUP"; echo "Backup ok ($(du -h "$BACKUP" | cut -f1))."

for id in "$@"; do
  f=$(ls backend/migrations/${id}_*.sql 2>/dev/null | head -1); [ -n "$f" ] || { echo "No migration $id"; exit 2; }
  name=$(basename "$f")
  if [ "$("${P[@]}" -Atc "select count(*) from public.raheel_migrations where name='$name'")" = 1 ]; then echo "skip $name (already applied)"; continue; fi
  case "$id" in
    020) [ "$("${P[@]}" -Atc "select to_regclass('public.optical_customers') is not null and to_regprocedure('public.upsert_optical_customer(jsonb)') is not null")" = t ] \
           || { echo "020 needs migrations 016-019 (optical tables and upsert_optical_customer) first."; exit 3; }
         [ "$("${P[@]}" -Atc "select to_regclass('public.optical_order_receipts') is null")" = t ] || { echo "020 objects already exist; recording it as applied."; "${P[@]}" -c "insert into public.raheel_migrations(name) values('$name')"; continue; } ;;
  esac
  echo "apply $name"; "${P[@]}" -f "$f"; "${P[@]}" -c "insert into public.raheel_migrations(name) values('$name')"
done
"${P[@]}" -Atc "select 'Order API version '||public.optical_orders_api_version()" 2>/dev/null || true
"${P[@]}" -Atc "select 'Orders: '||count(*) from public.optical_orders" 2>/dev/null || true
echo "Done. Backup kept at $BACKUP"
