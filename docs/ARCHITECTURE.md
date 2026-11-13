# Architecture

Raheel Optics is an offline-first Android app (Kotlin, Jetpack Compose) for an optical shop's orders and customers,
backed by Supabase. Every change is saved on the phone first and synced to the cloud when a connection is available.

```
┌──────────────────────── Android app (pk.raheeloptics.orders) ────────────────────────┐
│ UI (Compose screens)  ──calls──▶  OrdersViewModel  ──calls──▶  OrderStore / OpticalStore │
│        ▲  observes UiState              │                            │ SQLite (PowerSync SDK) │
│        └────────────────────────────────┘                            ▼                        │
│                                         enqueueSync ─▶ SyncWorker ─▶ SupabaseSynchronizer   │
└──────────────────────────────────────────────────────────────────────────┬────────────┘
                                                                           │ HTTPS (REST + RPC)
┌──────────────────────────── Supabase ─────────────────────────────────────▼────────────┐
│ Auth (email + 6-digit codes, Gmail SMTP) · Postgres tables + RPC functions (row-level    │
│ security per account) · Storage bucket optical-frames · Edge function delete-account     │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

## Layers

| Layer | Package | Responsibility |
|---|---|---|
| App shell | `RaheelApp`, `MainActivity` | Creates singletons (sessions, auth, reminders, branding), opens the account's local database, runs sync. |
| UI | `ui` | Compose screens. Each screen is a file; screens read `UiState` and call `OrdersViewModel`. No database code. |
| State | `ui/OrdersViewModel.kt` | Holds `UiState` (a `StateFlow`), turns user actions into store calls, refreshes the visible page when the database changes. |
| Data | `data` | `OrderStore` (orders, inbox, export/import, paging), `OpticalStore` (customer profiles), `AppSchema` (local tables), `WorkspaceFiles` (database file names). |
| Domain | `core` | Plain Kotlin models and rules: `Order`, `Customer`, `OrderStatus`, validation (`FieldRules`, `Rules`), money, invoice text, wire format (`OrderWire`). No Android UI. |
| Identity | `identity` | `AuthClient` (Supabase Auth calls), `SessionStore` (encrypted session + workspace registry), `TokenTransport` (HTTP with token refresh). |
| Sync | `sync` | `enqueueSync`/`SyncWorker` (WorkManager job when online) and `SupabaseSynchronizer` (upload queue, download feed). |
| Reminders | `reminders` | `ReminderScheduler` sets exact alarms for promised/reminder times and posts notifications. |
| Other | `branding`, `updates`, `onboarding`, `CrashReporting` | Logo/name from the server, forced-update gate, first-run intro, Firebase Crashlytics (release builds only). |

## Source map (UI)

| File | What it contains |
|---|---|
| `AppRoot.kt` | Top-level composable: picks splash / onboarding / sign-in / main scaffold; bottom navigation; dialogs shared by tabs. |
| `AppTheme.kt` | Colours, light/dark schemes, `AppTheme`. |
| `CommonUi.kt` | `BrandLogo`, `Message` (auto-hides after 5 s), `EmptyState`, `HelpTip`, date helpers. |
| `Components.kt` | Shared form controls (`AppTextField`, `FullScreenForm`, `TipIconButton`, spacing tokens `Ui`). |
| `AppLoader.kt` | Spinner and `LogoLoader` (logo inside a spinning ring) shown while a screen loads. |
| `OrdersScreen.kt` | Orders tab: status tabs, search, filter sheet, `OrderCard` with swipe actions. |
| `OrderDetails.kt` | Order details: status buttons, WhatsApp pickup message, repeat order. |
| `OrderEditor.kt` | Create / edit order form, prescription editor, photo picker. |
| `CustomersScreen.kt` | Customer directory, customer orders dialog, customer details sheet. |
| `AnalyticsDashboard.kt` | Sales/collection analytics for a period. |
| `InboxScreen.kt` | Reminder and deadline notifications list. |
| `SettingsScreen.kt` | Settings, storage, password, privacy, export/import, delete account. |
| `ShopSettings.kt` | Shop name, WhatsApp number, hours, location. |
| `AccountScreens.kt` | Splash, sign-in / create account, email code, forgot password. |
| `OnboardingScreen.kt` | First-run introduction. |

## Local database

One SQLite file per signed-in account: `orders-<project>-<user>.db` (guest: `orders-local-device.db`). Builds before
the rename used `todos-*.db`; `WorkspaceFiles.migrateLegacy` renames those on first launch.

| Table | Purpose |
|---|---|
| `server_todos` | Orders as last confirmed by the server. |
| `drafts` | Local edits not yet confirmed (overlay on `server_todos`). |
| `journal` | Every local change as an operation: `queued` → `acked` or `conflict`. |
| `operation_requests` | Upload queue read by the sync engine (PowerSync CRUD queue). |
| `history_cache` | Recent order changes, used by guest transfer and conflict checks. |
| `optical_customer_cache`, `optical_drafts`, `optical_journal` | Same pattern for customer profiles. |
| `inbox`, `reminder_receipts`, `deadline_receipts` | Notifications and which ones were already delivered. |
| `sync_checkpoint`, `server_totals`, `resource_limits`, `upload_pause`, `quarantined_changes` | Sync bookkeeping. |
| `local_state`, `local_totals`, `active_todos`, `user_totals`, `import_receipts` | Guest mode and imports. |

The orders table is still named `server_todos` and the model's JSON still has `status: "active"`; renaming stored
tables or JSON keys would need a data migration and gives no user benefit.

## Build variants

| Variant | App ID | Use |
|---|---|---|
| `debug` | `pk.raheeloptics.orders` | Development. |
| `qa` | `pk.raheeloptics.orders.qa` | Side-by-side test build; instrumented tests run against it. |
| `perf` | `pk.raheeloptics.orders.perf` | Release-like (R8, not debuggable) for startup measurements. |
| `release` | `pk.raheeloptics.orders` | Signed with `RELEASE_STORE_*` from `.env`. |

See [FLOWS.md](FLOWS.md) for step-by-step call chains and [BACKEND.md](BACKEND.md) for the server.
