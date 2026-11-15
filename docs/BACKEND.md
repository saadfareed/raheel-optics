# Backend (Supabase)

There is no server of our own. Everything runs on one Supabase project (currently `nfnumouljfpvumzuorav`, Tokyo).

| Part | Where | Status | Purpose |
|---|---|---|---|
| Database tables + RPC functions | `supabase/migrations/001–024` | Applied | Stores orders, customers, payments and history per account; validates every change and assigns revisions so phones don't overwrite each other. |
| Row-level security | in the migrations | Applied | Each account can read and write only its own rows. |
| Auth | Supabase dashboard / Management API | Configured | Email sign-up with 6-digit codes; Gmail SMTP (`smtp.gmail.com`, sender "Raheel Optics"); 30 emails/hour. |
| Email templates | `supabase/auth-email-templates.json` | Applied | Branded HTML for sign-up, password reset and sign-in codes (`{{ .Token }}`). |
| Storage bucket `optical-frames` | migration 016 | Created | Private frame photos (not used by the app yet; photos are device-only). |
| Edge function `delete-account` | `supabase/functions/delete-account` | Deployed by `.github/workflows/delete-account-function.yml` | Called by Settings → Delete account and the deletion page. Required by Google Play. |
| Release site | `release-site/` | GitHub Pages via `.github/workflows/release-site.yml` | Privacy policy and account-deletion pages required by Google Play. |
| Keep-alive and backups | `.github/workflows/keep-alive-backup.yml` | Daily at 02:30 PKT | Stops the free project pausing; saves an encrypted backup (kept 30 days). |
| Update policy | `app_update_policy` (migrations 006, 024) | Applied (v1.5.1, min 1) | Remote soft/forced update. Raise `minimum_version_code` to force everyone off a broken build (example in 024). |

## Functions the app calls

| RPC (`/rest/v1/rpc/<name>`) | Called from | Does |
|---|---|---|
| `optical_orders_api_version` | `SupabaseSynchronizer.requireOrderApi` | Returns the API version (must be ≥ 3). |
| `apply_optical_order` | `SupabaseSynchronizer.uploadOrder` | Creates or edits an order (full payment ledger) if `baseRevision` matches; returns accepted/conflict. |
| `upsert_optical_customer` | `SupabaseSynchronizer.uploadCustomer` | Creates or updates a customer profile; rejects a mobile number owned by another customer. |
| `optical_order_feed` | `SupabaseSynchronizer.download` | Pages of orders changed since a cursor. |
| `/functions/v1/delete-account` | `AuthClient.deleteAccount` | Verifies the password and deletes the user and their data. |
| `app_update_policy`, `app_appearance` (tables) | `UpdateController`, `BrandingController` | Forced-update rules and shop name/logo. |

Full request/response shapes: [OPTICAL-API.md](OPTICAL-API.md).

## Migrations

001–015 build the original sync engine (operation receipts, revisions, quotas, history). 016–022 add the optical shop
(customers, orders, payments, events, frame images, shop hours, order feed) and copy any older orders across. 023
retires the to-do API. 024 publishes the Android update-policy row. Apply them in order; each runs once and is recorded in `public.raheel_migrations`.

```bash
scripts/apply-migrations.sh 001 002 003 004 005 006 007 008 009 010 011 012 013 014 015 016 017 018 019 020 021 022 023 024
```

`DATABASE_URL` must work over IPv4; use the session pooler
(`postgresql://postgres.<ref>:<password>@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres`). The script backs
up the `public` schema to `build/backups/` first.

## Checks (no Supabase needed)

| Script | Checks |
|---|---|
| `scripts/backend-gate.sh` | Applies all migrations to a throwaway local PostgreSQL and runs `supabase/tests/*.sql`. |
| `scripts/wire-contract-check.sh` | Replays the app's real request payloads against the migrations. |
| `scripts/device-gate.sh` | Runs the instrumented tests on a connected phone against the QA build. |

## Backups (free plan)

The free plan pauses after 7 days without activity and has no downloadable backups. The daily workflow
`Keep alive and back up` queries the database (which counts as activity) and saves
`raheel-optics-YYYYMMDD.tar.gz.gpg` (shop data + sign-in accounts, AES-256) as a workflow artifact for 30 days.
Needs repo secrets `DATABASE_URL` and `BACKUP_PASSPHRASE` (both in `.env`). Lose the passphrase and the
backups cannot be opened.

If the project does pause: Supabase dashboard > project > Restore (possible for 90 days). Phones keep working offline and
sync when it is back.

Restore into a fresh project (apply migrations 001–024 first):

```bash
gh run download <run-id> -n db-backup-<run-id>
gpg -d raheel-optics-YYYYMMDD.tar.gz.gpg | tar -xz        # asks for BACKUP_PASSPHRASE
pg_restore -d "$DATABASE_URL" --data-only --disable-triggers auth.dump
pg_restore -d "$DATABASE_URL" --data-only --disable-triggers public.dump
```

## Before release

1. Push to GitHub, then in the repo settings:
   - Pages > Source: **GitHub Actions**.
   - Secrets and variables > Actions: secret `SUPABASE_ACCESS_TOKEN`, variable `SUPABASE_PROJECT_REF`.
   Both workflows also run from the Actions tab ("Run workflow").
2. Check that `RELEASE_PRIVACY_URL` and `RELEASE_ACCOUNT_DELETION_URL` in `.env` match the Pages address
   (`https://<user>.github.io/<repo>/privacy.html` and `.../delete-account.html`); they are built into the app.
3. Rotate the database password and `SUPABASE_ACCESS_TOKEN` if this project becomes production, or create a fresh
   project and apply the migrations there.
4. Back up `.engineering/signing/raheel-optics-release.jks` and its password from `.env`.
