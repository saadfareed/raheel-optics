# Raheel Optics

Standalone Android and Supabase optical-shop order manager cloned from Daymark's
offline-first foundation. This directory builds and deploys independently from the
original app in the parent repository.

## Android demo

```sh
JAVA_HOME=/home/saad/jdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application ID: `pk.raheeloptics.orders`.

## Backend

Apply the SQL files in `supabase/migrations` to a separate Supabase project. Do not
point this clone at Daymark's production project. Configure public client values in
`.env` using `.env.example` as the template.
