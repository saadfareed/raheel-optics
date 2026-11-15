# Releasing a version

## Build

1. In `app/build.gradle.kts` raise `versionCode` (must always increase; Play rejects a repeat) and set `versionName`.
2. Run the checks:
   ```bash
   export JAVA_HOME=~/jdk
   ./gradlew :app:testQaUnitTest          # unit tests
   scripts/device-gate.sh                 # phone connected; QA build, never touches the real app
   ```
3. Build the signed bundle: `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
   The build stops if `.env` lacks the Supabase HTTPS URL, a public key, or the keystore and its passwords.

## Publish

Upload the `.aab` in Play Console (internal testing first, then production). Play Console needs, once:
- Privacy policy: `https://saadfareed.github.io/raheel-optics/privacy.html`
- Account deletion: `https://saadfareed.github.io/raheel-optics/delete-account.html`
- Data safety: name, email, phone number (customer contacts), health information (eye prescriptions), photos stay on
  the device, crash logs; data is encrypted in transit; users can request deletion.
- Support email: `RELEASE_SUPPORT_EMAIL` from `.env` (the same address the app and privacy page show).

## Tell installed apps about it

The app reads `public.app_update_policy` on start and every 15 minutes. Change it only after the new version is live
on Play, otherwise users are sent to a store page with nothing to install.

```sql
-- Optional update: dismissible banner for anyone below 10
update public.app_update_policy set latest_version_code=10, version_name='1.5.2', updated_at=now()
where platform='android';

-- Forced update (a broken or unsafe version): anyone below 10 is locked until they update
update public.app_update_policy set latest_version_code=10, minimum_version_code=10, version_name='1.5.2',
  message='This version has a problem. Please update to keep using Raheel Optics.', updated_at=now()
where platform='android';
```

Run it in the Supabase SQL editor or with `psql "$DATABASE_URL"`.

## Backend changes

- New migration: add `supabase/migrations/NNN_name.sql`, run `scripts/backend-gate.sh` against a local PostgreSQL,
  then `scripts/apply-migrations.sh NNN` (backs up the `public` schema first).
- Delete-account function: push to `main`; `.github/workflows/delete-account-function.yml` deploys it.
- Privacy or deletion page: edit `release-site/`, push to `main`; `.github/workflows/release-site.yml` publishes it.

## Signing key

`.engineering/signing/raheel-optics-release.jks`, alias `optics`, passwords in `.env`. Keep a copy of the file and the
password together outside this computer. If Play App Signing is on, this is the upload key and Google can reset it;
otherwise losing it means a new Play listing.
