# Raheel Optics

Android app for an optical shop: customers, prescriptions, orders, payments, pickup reminders and WhatsApp messages.
Works offline and syncs between phones through Supabase.

## Docs

- [Architecture](docs/ARCHITECTURE.md): layers, source map, local database, build variants
- [Flows](docs/FLOWS.md): step-by-step call chains for every feature
- [Backend](docs/BACKEND.md): Supabase setup, functions, migrations, release checklist
- [Order API](docs/OPTICAL-API.md): RPC request/response contract

## Build

Needs a JDK with a compiler (`~/jdk`) and the Android SDK.

```bash
export JAVA_HOME=~/jdk
./gradlew :app:installQa              # side-by-side test build (pk.raheeloptics.orders.qa)
./gradlew :app:testQaUnitTest         # unit tests
./gradlew :app:connectedQaAndroidTest # on-device tests (phone connected)
./gradlew :app:assembleRelease        # signed release (needs RELEASE_STORE_* in .env)
```

## Configuration

- `app/cloud.properties`: public Supabase URL and anon key (safe to commit; never put passwords or service keys here).
- `.env` (git-ignored, template `.env.example`): database URL for migrations, release signing, SMTP settings.
- `app/google-services.json` (git-ignored): enables Firebase Crashlytics in release builds.

## Layout

```
app/                  Android app (Kotlin, Jetpack Compose)
supabase/migrations/  Database schema and RPC functions (001–023)
supabase/tests/       SQL tests run by scripts/backend-gate.sh
supabase/functions/   Edge function delete-account
release-site/         Privacy and account-deletion pages for Google Play
scripts/              Migration, backend and device test scripts
branding/             Logo sources
```
