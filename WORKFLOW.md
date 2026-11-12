# Raheel Optics — fix workflow (parallel tracks + on-device gate)

Orchestrator instructions. Run from the repo root. Decisions already made by the owner:
Supabase project is separate from Daymark · finish moving to `optical_*` tables ·
multiple devices must work · rename Daymark → Raheel Optics last.

## Ground rules
- One git worktree + branch per track: `git worktree add .worktrees/<track> -b fix/<track>`.
  Tracks in the same wave must not edit the same files (ownership listed below).
- A track is done only when its own tests pass **and** `scripts/device-gate.sh` (or
  `scripts/backend-gate.sh` for SQL-only tracks) passes on the connected phone/local DB.
  A green build alone is not acceptance.
- The phone is a single shared resource: `device-gate.sh` takes a lock, so parallel tracks
  queue for it instead of fighting over the device.
- Never apply migrations to the live Supabase project. Local PostgreSQL only until the owner
  approves rollout. Never clear app data on the phone (`adb install -r`, never uninstall).
- Merge each track to `main` only after its gate passes; rebase later waves on `main`.

## Phone setup (once)
Developer options ON · USB debugging ON · on Xiaomi/Redmi also "Install via USB" and
"USB debugging (Security settings)" · unlock screen, keep awake · accept the RSA prompt.
Check: `adb devices` shows `device`. Set `ANDROID_SERIAL` if more than one is attached.

## Wave 0 — baseline (serial)
0.1 Commit the current tree on `main` (a `.git` exists now; check `git status`, ensure
    `.env`, `local.properties`, `build/`, keystores are ignored).
0.2 `scripts/device-gate.sh` on the untouched tree → record the baseline (tests passing, APK ok).

## Wave 1 — parallel (no shared files)
| Track | Owns | Work | Tests |
|---|---|---|---|
| **A-backend** | `backend/**`, `supabase/**` | Migration 020: `apply_optical_order` RPC (revision + idempotency receipts, admission reuse) writing `optical_orders`/`optical_payments`/`optical_order_events`; refunds as signed ledger rows (add `kind`, relax `amount_paisa>0` for refunds); full server validation of prescription/frame/lens (types, ranges, AXIS 1–180, prism ≥0); order page RPC with search by name/normalized mobile/order number *before* pagination; terminal orders (complete/cancel) readable by pages; server-side analytics RPC (sales, collected, refunds, outstanding, counts, by period, Asia/Karachi); indexes for those queries; retire todo-only tables/functions in a *separate, clearly marked* migration 021 (not applied until owner approves). | `backend/tests/*.sql` + new suites; `scripts/backend-gate.sh` |
| **B-images** | `ui/FrameImages.kt`, new `data/ImageSync*.kt`, `OrderDetails.kt` image bits | Durable upload queue to private bucket `optical-frames` (path `<uid>/<orderId>/<imageId>.jpg`), retry/backoff, download+cache on other devices, `optical_frame_images` rows, remove/replace, export notes. | unit tests + instrumented offline→online test |
| **C-hygiene** | `.gitignore`, `proguard-rules.pro`, `backend/mail/**`, `release-site/**`, `DaymarkApp.kt` | Single `workspaceFilename()` helper (currently duplicated 3×); replace fully-qualified names with imports; release build smoke (`assembleRelease` with minify) and fix ProGuard keeps; mail templates/brand strings and default support address from env only (no hard-coded mailbox); SMTP provider note + outbox health. | `python -m pytest backend/mail backend/tests`, `node --test release-site`, gate |

## Wave 2 — depends on A (serial inside, parallel with B/C leftovers)
| Track | Owns | Work |
|---|---|---|
| **D-sync** | `sync/**`, `data/OpticalStore.kt`, `data/AppSchema.kt`, `backend/sync-streams.yaml` | Route all order/customer ops through A's RPCs; sync stream for `optical_*` incl. completed/cancelled orders and payments; `OpticalStore` becomes the only order write path; keep todo-compat path only behind a one-release migration shim that drains queued ops, then delete. Multi-device conflict UX stays (rebase / use cloud). |
| **E-analytics** | `core/OrderAnalytics.kt`, `ui/AnalyticsDashboard.kt` | Use server aggregates when online, device aggregates offline; label the source; reconcile Sales/Collected/Outstanding against payment ledger. |

## Wave 3 — performance and structure (serial, touches UI files)
F-perf: split `DaymarkScreen.kt` (113 KB) into screen files (Orders, Customers, Analytics, Settings, Editor);
move list search/filter/sort to indexed SQL columns instead of JSON parsing; profile with
`adb shell dumpsys gfxinfo pk.raheeloptics.orders` before/after and record jank %.
Add a 5 000-order seeded-DB instrumented test for list/search latency.

## Wave 4 — rename (serial, last; touches everything)
Package/namespace `com.daymark.todo` → `pk.raheeloptics.orders`; DB filename prefix
`todos-` → `orders-` **with a one-time on-device file migration** (no data loss); keystore
alias/filename, theme/style names, `Daymark*` classes, notification channels, docs. Re-run the
whole gate and the manual acceptance cycle below.

## Per-track procedure (each agent)
1. Read the owned files and the relevant tests first; write/adjust tests before code.
2. Implement; run `./gradlew :app:testQaUnitTest` (or the backend gate).
3. Run `scripts/device-gate.sh`; attach `build/device-evidence/<run>/` to the PR note.
4. Report: what changed, expected vs actual, remaining limits. Do not mark done on build success alone.

## Manual acceptance cycle (human + `device-gate.sh --manual-pause`)
Use labelled demo data; never message real customers.
1. Create customer + order with both-eye prescription, photo, partial payment, promised date, reminder.
2. Search by formatted/local/+92/partial mobile; combine filters and sorts.
3. Repeat order; edit profile; confirm old order prescription unchanged.
4. Active → Ready (swipe) → Undo → Ready → Complete; open WhatsApp draft without sending.
5. Refund a payment; confirm Sales/Collected/Outstanding reconcile.
6. Sunday hours, lunch break, special closure persist.
7. Airplane mode: create/edit, restart app, reconnect → no duplicates, no lost fields/images.
8. **Second phone** (same account): orders, customers, payments, images and analytics match.
9. Short reminder fires; notification permission denied path.
