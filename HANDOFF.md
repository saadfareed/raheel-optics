# Handoff (read this first)

Repo: Android app `pk.raheeloptics.orders` (Kotlin/Compose) + Supabase. Orders and customers sync only through the
optical API (docs/OPTICAL-API.md). The legacy to-do server API is retired by migration 023.

## State
- Backend: migrations 020-023 pass `scripts/backend-gate.sh` and `scripts/wire-contract-check.sh` on local PostgreSQL.
  Apply to Supabase with `scripts/apply-migrations.sh 020 021 022 023` (needs DATABASE_URL in .env; backs up first).
- `app/cloud.properties` points at a project URL that does not resolve; set the real project URL and anon key
  (or SUPABASE_URL / SUPABASE_PUBLIC_KEY in .env) before cloud sign-in can work.
- Instrumented tests run against the side-by-side QA build (`pk.raheeloptics.orders.qa`) via `scripts/device-gate.sh`;
  the real app's data is never touched. Unit tests: `./gradlew :app:testQaUnitTest`.

## Crash reporting (Firebase Crashlytics)
Wired in (`CrashReporting.kt`, Gradle plugins applied only when a config file exists). To switch it on:
1. Firebase console > create/open a project > add Android apps for `pk.raheeloptics.orders` (and `.qa`, `.perf` if you want them).
2. Download `google-services.json` into `app/` (git-ignored). Rebuild.
3. Collection is off in debug/QA builds and on in release/perf builds. Without the file the app builds and reports nothing.
Server-side only: the mail webhook can still signal errors to Sentry through `SENTRY_DSN_BACKEND` (optional).

## Next
1. Apply migrations, then sign in on two phones and check orders, payments and statuses match (device gate + manual).
2. Rename (last): package `com.daymark.todo`, local DB prefix `todos-` (with on-device file migration), `Todo` model.
3. Frame photo upload to the private `optical-frames` bucket (photos are device-local today).
