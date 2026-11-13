# Flows: what calls what

Paths are under `app/src/main/java/pk/raheeloptics/orders/`. `VM` = `ui/OrdersViewModel.kt`,
`Store` = `data/OrderStore.kt`. Every VM action runs through `VM.action { }`, which sets `busy`, catches errors into
`UiState.message` (shown by `Message`, auto-hidden after 5 s) and refuses to start while another action runs.

## 1. App start

1. Android starts `RaheelApp.onCreate`:
   - `CrashReporting.start` (Crashlytics on in release builds only)
   - `syncLaunchTheme` (light/dark splash)
   - `SessionStore(this)` (encrypted prefs)
   - `WorkspaceFiles.migrateLegacy` renames old `todos-*.db` files to `orders-*.db`. If anything was renamed, it then runs `SessionStore.migrateWorkspaceNames` and cancels background jobs queued by the old build.
   - creates `AuthClient`, `ReminderScheduler`, `BrandingController` and `UpdateController`
2. `MainActivity.onCreate`: `BrandingController.refresh()` in the background, then
   `setContent { AppTheme { UpdateGate { AppRoot() } } }`.
3. `AppRoot` creates `OrdersViewModel`, and its `init` runs `VM.attach()`:
   1. `SessionStore.session()`: no session means `loading=false`, and `AppRoot` shows `OnboardingScreen` (first run) or `AccountScreen`.
   2. `RaheelApp.workspace()` opens `orders-<key>.db` with `PowerSyncDatabase(appSchema)` and wraps it in `OrderStore`.
   3. `Store.reconcile()` (background thread) drops confirmed operations and trims caches.
   4. `VM.refresh()` (background thread) reads the visible data:
      - `Store.todoPage(query)` gets the current page of orders (10 per page)
      - `Store.customerDirectory` runs only while the Customers tab is open
      - `Store.uiSnapshot` gets totals, conflicts, inbox and status counts
      - the results are published as `UiState`
   5. `RaheelApp.refreshReminders()` re-arms the alarms (`ReminderScheduler.reconcileSchedules`).
   6. Observers: `db.onChange(tables)` → `VM.refresh()` (debounced 75 ms); inbox changes → `refreshInbox()`.
   7. Signed-in accounts also call `enqueueSync` if anything is waiting.
4. Log tag `RaheelStartup` prints each phase. `ReportDrawnWhen` reports "fully drawn" once the first page is shown.

## 2. Create account (email code)

1. `AccountScreens.AccountScreen` → `VM.register(email,password)` → `AuthClient.register` → `POST /auth/v1/signup`.
2. Supabase emails a 6-digit code (template `supabase/auth-email-templates.json`, sent through Gmail SMTP).
3. `register` returns false (not confirmed yet), so the VM sets `UiState.codeEmail`, and `AccountScreen` shows `EmailCodeScreen`.
4. The user enters the code → `VM.verifyCode(code,null)` → `AuthClient.verifyCode(type="signup")` → `POST /auth/v1/verify`.
5. `saveResponse` stores the session (`SessionStore.save`) → `VM.attach()` (flow 1, step 3).
6. "Send a new code" → `VM.resendCode` → `AuthClient.resendSignupCode` → `POST /auth/v1/resend`.

## 3. Sign in / forgot password / sign out

- **Sign in:** `VM.login` → `AuthClient.login` → `POST /auth/v1/token?grant_type=password` → `attach()`.
- **Forgot password:**
  1. `ForgotPasswordScreen` → `VM.reset` → `AuthClient.reset` → `POST /auth/v1/recover` emails a code, and `codeIsReset=true`.
  2. `EmailCodeScreen` asks for the code and a new password → `VM.verifyCode(code,newPassword)` → `verify(type="recovery")`, then `PUT /auth/v1/user {password}`.
- **Token refresh:** `TokenTransport` refreshes the token automatically before calls (`AuthClient.validSession`).
- **Sign out:** `VM.logout` → `RaheelApp.logout`:
  1. revokes the session (`/auth/v1/logout`)
  2. `clearLocalAccount` clears the session, cancels sync and reminders, and deletes data only for "Clear device data"
- **Change password:** Settings → `PasswordDialog` → `VM.changePassword` → `AuthClient.changePassword`. It re-checks the current password first.
- **Delete account:** Settings → `VM.deleteAccount` → `AuthClient.deleteAccount` → `POST /functions/v1/delete-account {current_password}` (edge function), then the local data is cleared.

## 4. Guest mode and moving guest orders into an account

1. `VM.continueAsGuest` creates a local-only session, which opens `orders-local-device.db`. Nothing is uploaded.
2. On sign-in, `SessionStore.prepareGuestTransfer` marks the guest data.
3. On the next sync, `RaheelApp.connect` → `transferGuestTasks(destination)` runs in pages of 10:
   - `Store.guestTransferPage` reads guest orders
   - `destination.receiveGuestTask` re-saves each one as a new queued create
   - when everything has moved, the guest database is deleted (`disconnectAndClear`)

## 5. Create / edit an order

1. `+` (`AppRoot` → `AddTaskButton`) or "Edit order" opens `OrderEditor.kt`. While the mobile number is typed, `VM.customerForMobile` looks up an existing customer.
2. "Save" → `OrderEditor.submit` validates the form (`FieldRules`, `Money.parse`), then `VM.save(order,before)`:
   1. `Rules.validate` checks the dates and reminder.
   2. `Store.save(order,"create"|"edit",before)` runs in one transaction:
      - checks that the version being edited is still current, otherwise it fails with "Open the latest version…"
      - writes `drafts` (the new state)
      - writes a `journal` entry (`queued`)
      - writes `operation_requests` (upload queue)
      - upserts the customer profile (`OpticalStore.upsertCustomer`) when the contact details changed
   3. `enqueueSync(app)` queues the upload, unless in guest mode.
   4. `VM.refresh()` updates the list immediately from `drafts`.

## 6. Order status actions

| User action | Where | Call chain | Allowed when |
|---|---|---|---|
| Swipe right on an order card | `OrdersScreen.OrderCard` | Active: confirm dialog → `VM.save(copy(status=READY_FOR_PICKUP))` | `ACTIVE` |
| Swipe right on an order card | `OrdersScreen.OrderCard` | Ready: opens `PickupMessageDialog` (the same as the WhatsApp button) | `READY_FOR_PICKUP` |
| "Ready for pickup" button | `OrderDetails` | `change(copy(status=READY_FOR_PICKUP))` → `VM.save` | `ACTIVE` |
| "Complete" | `OrderDetails` | `VM.transition(order,"complete")` → `Store.transition` → `Rules.transition` → `Store.save` | `READY_FOR_PICKUP` |
| "Cancel order" | card / `OrderDetails` | confirm dialog → `VM.transition(order,"delete")` (the order stays with status Cancel) | `ACTIVE` |
| "Undo" after marking ready | snackbar | `VM.undoReady(before)` | right after marking ready |

## 7. WhatsApp messages

- **Pickup message** (ready orders), opened by swipe or button:
  1. `OrderDetails.PickupMessageDialog` → `ShopPreferences.load` → `shop.readyMessage(order,window,balance)` builds editable text.
  2. "Open WhatsApp" → `WhatsAppShare.send(context,mobile,message,InvoiceBuilder.build(...))`.
  3. That renders an invoice picture and opens WhatsApp addressed to the customer. If WhatsApp isn't installed, the system share sheet opens instead.
- **Dues statement:** `CustomersScreen` → WhatsApp icon → `shop.duesMessage(customer,orders)` → `WhatsAppShare.send` with the open orders.

## 8. Sync with Supabase

1. **Trigger:** `enqueueSync` (after any save, at startup, or on sign-in) queues one WorkManager job, `todo-sync`, that runs only with network. It retries with exponential backoff (30 s) and keeps at most one job waiting.
2. `SyncWorker.doWork` keeps calling `RaheelApp.connect()` until `hasSyncWork()` is false (45 s timeout, then retry).
3. `RaheelApp.connect` runs under `syncLock`, then `SupabaseSynchronizer(store,auth).exchange()`.
4. **Upload:** `exchange` → `uploadHead()` repeated, up to 200 per pass or 15 s. Each pass reads the head of the queue with `db.getCrudBatch(1)`:
   - **Customer change** → `uploadCustomer` → `POST /rest/v1/rpc/upsert_optical_customer`. If accepted, `OpticalStore.acceptCustomer`; otherwise it is recorded as a conflict.
   - **Order change** → `requireOrderApi()` (once per run: `optical_orders_api_version` must be ≥ 3) → `uploadOrder` → `POST /rest/v1/rpc/apply_optical_order` → `Store.recordResult` (`acked` or `conflict`).
   - **Unreadable change** → `Store.quarantineQueuedChange`, so it never blocks the queue.
   - **Quota or admission error** → `Store.pauseUploads`. The upload stops; downloads still run.
5. **Download:** `download()` calls `POST /rest/v1/rpc/optical_order_feed` with the stored cursor (`Store.syncCursor`), then `Store.applyServerPage(rows,cursor,totals,limits)`. That writes `server_todos` and `sync_checkpoint`.
6. `Store.reconcile()` drops `drafts`/`journal` rows the server has confirmed.
7. `syncEvents` is bumped. The VM's `db.onChange` observer refreshes the visible page, so the other phone's change appears.
8. **Conflicts:** an edit made from an out-of-date version is rejected by the server and shown in the Orders tab. The user picks one:
   - `VM.resolveConflict(p,saveAsNew)` → `Store.keepRemote`
   - or save it as a new order

## 9. Customers

- **Open tab:** `VM.openCustomers()` sets `customersOpen=true`, downloads profiles in pages of 100 (`OpticalStore.cacheCustomers`), then refreshes.
- **List:** `Store.customerDirectory(query)` groups orders by customer or mobile number, with search, sort and load-more (10 per page).
- **Edit a customer:** `CustomerDetailsSheet` → `VM.saveCustomer`, which calls either:
  - `OpticalStore.upsertCustomer`, which queues a `customer_upsert` operation
  - `OpticalStore.retryCustomerChanges` (to resolve a conflict)
- **Order history:** `VM.ordersForCustomer` → `Store.customerOrders`.

## 10. Reminders and notifications

1. Saving an order with `reminderAt`/`dueAt` changes the database, and the VM observer calls `RaheelApp.refreshReminders`:
   - `Store.notificationSchedules()` lists the upcoming times
   - `ReminderScheduler.reconcileSchedules` sets exact alarms (`AlarmManager`)
2. **Alarm fires:** `ReminderReceiver.onReceive`:
   - `Store.activeTodo(id)` checks the order is still active and the time still matches
   - `Store.addTaskAlert` adds an inbox row
   - `ReminderScheduler.notify` posts the notification
   - `reconcileSchedules` re-arms the next alarm
3. **Reboot, time change or app update:** `BootReceiver` → `ReminderWorker`:
   - `Store.deliverDueTaskAlerts` delivers anything missed
   - `notifyAlerts` posts them; more than 5 missed reminders become one summary notification
   - `reconcileSchedules` re-arms the alarms
4. **Inbox tab:** `InboxScreen` lists `snapshot.inbox`. Tapping a notification → `VM.readAlert` / `VM.openTask`.

## 11. Analytics

`AnalyticsDashboard` (Analytics tab) → `VM.loadOrderAnalytics(query)` → `Store.orderAnalytics(query)`: an SQL summary of
sales, collected and refunded amounts, plus order counts by status for the chosen period. It runs entirely on the
device.

## 12. Export / import

- **Export:** Settings → `VM.exportWorkspace(uri,selection)` → `Store.exportTo(writer)` writes a JSON backup (orders, customers).
- **Import:**
  1. `VM.previewTaskImport(uri)` → `TaskImport.preview` shows the counts.
  2. `VM.applyTaskImport` re-saves each active order as a new create, and gives each one a new import number.
  3. Re-importing the same file resumes safely (`import_receipts`).

## 13. Frame photos

`OrderEditor.importPhoto` → `FrameImages.import(context,uri)` re-encodes the photo as JPEG, lowering quality and then
size until it is at most 1 MB. If that isn't possible, it shows "can't be reduced below 1 MB". The file is stored in
the app's private storage, and its id is kept on the order. Photos are **device-only** today: the Supabase bucket
`optical-frames` exists, but nothing uploads to it yet.

## 14. Startup timing

`adb logcat -s RaheelStartup ActivityTaskManager` shows each phase and the "Fully drawn" time. The perf build reaches
a usable Orders list in about 1.4–2.2 s. The debug and QA builds are slower because Compose code isn't optimised in
debuggable builds.
