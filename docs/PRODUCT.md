# Raheel Optics: product guide

What the app does, for whom, how each part behaves, where data lives, how it is kept safe and how it is run. For code
structure see [ARCHITECTURE.md](ARCHITECTURE.md); for call chains see [FLOWS.md](FLOWS.md); for the server see
[BACKEND.md](BACKEND.md); for shipping a version see [RELEASING.md](RELEASING.md).

## 1. The problem and who it is for

A neighbourhood optical shop in Pakistan takes an eye test, sells a frame and lenses, sends the job to a lab and asks
the customer to come back in a few days. Today that runs on a paper register and the owner's WhatsApp:

- prescriptions are lost or unreadable when the customer returns a year later;
- nobody remembers which glasses were promised for today, so customers arrive to "not ready yet";
- part-payments and balances are tracked in heads and notebooks;
- pickup messages, payment details and review requests are typed by hand, one customer at a time.

Raheel Optics is an Android order book built for that counter. Users are the shop owner and staff, on one or more
phones. Customers never install anything; they receive WhatsApp messages from the shop.

## 2. What it does

| Area | What the user gets |
|---|---|
| Orders | One form for customer, mobile, full prescription (distance, near, ADD, PD, prism), frame, lens, prices, discount, extras, payments and promised date. Order numbers like `RO-261115-7KQ2M`: date-sortable, easy to read aloud, safe to create offline. |
| Status flow | Active → Ready for pickup → Complete, or Cancel. Swipe a card to mark ready; undo right after. Tabs: All, Active, Ready, Completed, Cancelled. |
| Payments | Several payments per order (cash, card, bank transfer, JazzCash, Easypaisa, other); refunds; balance shown everywhere. Amounts are stored in paisa, never floating point. |
| Customers | Directory built from orders and saved profiles, searchable by name or mobile; full order history and latest prescription per customer; "repeat order" copies the last prescription. |
| WhatsApp | Ready-to-edit messages: order confirmation with an invoice picture, pickup message with 2-hour slots from the shop's hours and the shop's payment accounts when money is owed, dues statement, feedback request with the shop's Google review link. The shop always presses Send itself. |
| Reminders | Notifications at the promised time and optionally before or after it, naming the customer, order, ready state and balance, with a "Call customer" button. Work offline and with the app closed. Inbox tab keeps the history. |
| Analytics | Sales, collected and refunded amounts and order counts by status for today, this week, month, quarter or a custom range. Computed on the phone. |
| Shop settings | Name, WhatsApp number, address and map pin, opening hours per day and exceptions, payment accounts, review link. |
| Accounts | Email sign-up with a 6-digit code, password reset by code, guest mode (phone only) with a one-way move into an account, change password, delete account in the app or on the web. |
| Backup | Export to a JSON file and import it back (resumes safely if interrupted). |
| Updates | Remote "new version available" banner and a forced-update lock for broken versions. |

Interface language is English; currency is PKR; shop time is Asia/Karachi.

## 3. How it behaves

### Offline first
Every change is written to the phone's own database first, so the app never waits for the network and works in a shop
with poor signal. A change is queued and uploaded when a connection appears. Several phones on one account converge:
each upload carries the version it was based on, and the server refuses an edit made from an out-of-date version. The
phone then shows a conflict and the user keeps the server's version or saves theirs as a new order. Nothing is
silently overwritten.

### Notifications
Scheduled on the phone (Android alarms), not sent from a server.

| When | Title example | Body |
|---|---|---|
| Reminder before the promised time | Ali Khan's glasses are due today 4:00 PM | Order RO-… · Not ready yet · PKR 3,000 due |
| Promised time reached, not ready | Ali Khan's glasses are due now | …plus "Check with the lab, or call to agree a new time." |
| Promised time reached, ready | Ali Khan hasn't collected their glasses | …plus "Call to arrange pickup and collect PKR 3,000." |
| More than 5 missed at once (e.g. phone was off) | 8 orders need attention | Open Inbox to see which customers are waiting. |

The lock screen shows only "An order needs your attention". Orders without a promised date get no notification.
Limits: Android may delay an alarm by up to about 15 minutes while the phone is idle; Xiaomi/Redmi phones need
"Battery saver: No restrictions" and "Autostart" for the app. Phones on the same account do not notify each other of
new orders (that would need server push, not built).

### Guest mode
Try the app without an account. Data stays on the phone and is never uploaded. Signing in later moves the guest
orders into the account and deletes the guest copy.

## 4. Data

| Data | Where | Notes |
|---|---|---|
| Orders, customers, payments, order history | Phone (SQLite) and Supabase Postgres | Per account. Prescriptions count as health information. |
| Sign-in session | Phone, encrypted with an Android Keystore key | Never in backups. |
| Shop settings and payment accounts | Phone only | Not synced; set up on each phone. |
| Frame photos | Phone only | Re-encoded to ≤ 1 MB. A storage bucket exists but nothing uploads yet. |
| Email address | Supabase Auth | Used for codes and password reset. |
| Crash reports | Firebase Crashlytics, release builds only | Technical details, no customer records. |

Android cloud backup and device-to-device transfer are switched off for all app data, so customer records never land
in a personal Google backup. Per-account limits on the server: 10,000 active orders, 240 changes a minute.

## 5. Security

- Each account can read and write only its own rows (Postgres row-level security); orders, payments and events can
  only be changed through validated server functions, never by direct table writes.
- The app ships only the public Supabase key; a release build refuses to compile with a secret key, a non-HTTPS URL
  or missing signing settings.
- HTTP without TLS is blocked; responses are size-limited; tokens refresh automatically and are revoked on sign-out.
- Deleting an account removes the account and all its server records (needs the current password). Copies on other
  phones are not erased remotely.
- Approximate location only, and only when the user taps "Use current location" in shop settings.

## 6. Running it

| Piece | Where | Cost |
|---|---|---|
| Database, auth, delete-account function | Supabase free plan, project `nfnumouljfpvumzuorav` (Tokyo) | Free |
| Privacy and account-deletion pages | GitHub Pages from `release-site/` | Free |
| Deploys | GitHub Actions on push to `main` | Free |
| Keep-alive and encrypted nightly backup (30 days) | GitHub Actions, 02:30 PKT | Free |
| Sign-up / reset emails | Gmail SMTP, 30 emails an hour | Free |
| Crash reports | Firebase Crashlytics | Free |

Free-plan limits to know: the project pauses after 7 days without activity (the nightly job prevents this; if it does
pause, restore it from the dashboard and phones sync when it is back); 500 MB database; no Supabase-side backups (the
nightly job covers this). Restore steps are in [BACKEND.md](BACKEND.md#backups-free-plan).

Private material kept off git, in `.env` and `.engineering/`: the signing keystore and its password, the backup
passphrase, the database URL and the Supabase access token. Losing the keystore or its password means no more updates
under the same Play listing; losing the backup passphrase makes the backups unreadable.

## 7. Known limits and next steps

- No push between phones (new-order alerts); would need Firebase Cloud Messaging and a server function.
- No default promised date; an order without one gets no reminder and no "Expected ready" line.
- Shop settings and frame photos are per phone.
- English only; one currency (PKR); Pakistan time for analytics.
- Built for one shop: default shop name and Google review link are Raheel Optics (editable in Settings).
- Support email is the owner's Gmail (`RELEASE_SUPPORT_EMAIL` in `.env`); a product address would suit selling to other shops.
