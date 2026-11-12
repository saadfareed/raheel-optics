# Verification — OpticalStore client write path (Phase 1, Task 2)

All commands run from the worktree `/home/saad/Code/raheel-optics/.worktrees/optical-store` on branch `optical-store`.
Device: `6pyl8llja66xzdam` (connected, confirmed via `adb devices`).

New source files:
- `app/src/main/java/com/daymark/todo/data/OpticalStore.kt`
- `app/src/androidTest/java/com/daymark/todo/OpticalStoreTest.kt`

TodoStore, the todo tables, UI, ViewModel, synchronizer, and backend are untouched.

## 1. JVM unit tests (no regressions) + test-count delta

`JAVA_HOME=/home/saad/jdk ./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**.

Total JVM unit tests after this task: **95** (summed from `app/build/test-results/testDebugUnitTest/*.xml`). The OpticalStore behaviors require a real PowerSync DB (native libs + Android `Context`), so they live in `androidTest` and run on-device — exactly how TodoStore's own store tests (`OfflineStoreTest`, `OrderStatusStoreTest`) are structured. The JVM suite is the regression gate for compilation + the pure-logic tests; it stays green with no new failures.

## 2. OpticalStore instrumented tests (the 8 required behaviors)

APKs installed on device, then run via the instrumentation runner:

```
adb -s 6pyl8llja66xzdam install -r --no-streaming .../app-debug.apk            → Success
adb -s 6pyl8llja66xzdam install -r --no-streaming .../app-debug-androidTest.apk → Success
adb -s 6pyl8llja66xzdam shell am instrument -w -e class com.daymark.todo.OpticalStoreTest \
    pk.raheeloptics.orders.test/androidx.test.runner.AndroidJUnitRunner
```

Output:

```
com.daymark.todo.OpticalStoreTest:........
Time: 1.721
OK (8 tests)
```

The new `OpticalStoreTest` class executes with **8 passing tests**, one per required behavior:
- `orderCreateWritesOneCustomerAndOneOrderInOneTransaction` — atomic customer+order creation.
- `knownNumberReusesExistingCustomerId` — second order with the same normalized mobile reuses the existing customer id (no duplicate).
- `replayingSameOperationIdIsANoOp` — replaying the same `OpticalOperation.id` is a no-op (single journal row).
- `offlineCreateThenReplayProducesNoDuplicates` — captured op replayed twice yields one order + one event.
- `phoneCollisionSurfacesDistinctOutcome` — `upsertCustomer` with a mobile belonging to a different customer throws the catchable `PhoneCollisionException` (right existing/attempted ids, no silent duplicate).
- `paymentLedgerAccumulatesAndBalanceIsCorrect` — two payments accumulate as two ledger entries with correct balance; overpayment rejected; refund raises the balance.
- `snapshotOverlayShowsQueuedOrderWithBalanceAndCustomerLinkage` — a queued order_create shows in `snapshot()` with correct computed balance and customer linkage.
- `editingCustomerLeavesPriorOrderPrescriptionSnapshotUnchanged` — editing the shared customer leaves a prior order's embedded prescription snapshot unchanged.

> Note on the runner: `connectedDebugAndroidTest` was blocked by an intermittent device user-restriction (`INSTALL_FAILED_USER_RESTRICTED`) during Gradle's uninstall/reinstall step. Setting `install_non_market_apps=1`, installing both APKs manually with `--no-streaming`, and invoking `am instrument` directly ran the identical suite on the identical runner.

## 3. assembleDebug (release gate)

`JAVA_HOME=/home/saad/jdk ./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**. (Only pre-existing deprecation warnings in unrelated UI code.)

## 4. App launches without crashing (store links cleanly)

There is no optical UI yet, so this only proves the new store compiles/links into the app.

```
adb -s 6pyl8llja66xzdam shell monkey -p pk.raheeloptics.orders -c android.intent.category.LAUNCHER 1
  → Events injected: 1
adb -s 6pyl8llja66xzdam shell pidof pk.raheeloptics.orders
  → 31945        (process alive)
```

`logcat` showed no `FATAL EXCEPTION` / `AndroidRuntime` / ANR lines for the package after launch.

## 5. Clean-cutover grep confirmations

```
grep -nE "active_todos|server_todos|FROM todos|\btodos\b|TodoStore|migrat" \
    app/src/main/java/com/daymark/todo/data/OpticalStore.kt
```
→ Only comment/doc references (e.g. "Mirrors TodoStore's cloud path", "the client writes the overlay draft only; ... never writes server_todos"). **No reads of `todos`/`active_todos`/`server_todos`** and no migration logic. OpticalStore reads only `optical_*` tables + the insert-only `operation_requests` CRUD channel.

```
grep -rniE "todo.*to.*optical|migrate.*optical|optical.*migrat" app/src/main/java
```
→ No migration code. (The single `Models.kt` hit is a pre-existing, unmodified comment about legacy todo decoding defaults; not introduced here and not migration logic.)

```
git diff --stat HEAD -- app/src/main/java/com/daymark/todo/data/TodoStore.kt \
                        app/src/main/java/com/daymark/todo/data/AppSchema.kt
```
→ empty (TodoStore.kt and AppSchema.kt unchanged).

## Design notes carried into the implementation

- Status persisted UPPERCASE client-side (`ACTIVE/READY_FOR_PICKUP/COMPLETE/CANCEL`); comments mark the later sync task's map to the backend lowercase enum. `@JsonNames` aliases on `OrderStatus` untouched.
- `total_paisa` never persisted as an authoritative client value — `OrderAmounts.totalPaisa` / `balancePaisa` / `paidPaisa` stay computed. The client writes only component amounts + the payment ledger.
- Mirroring TodoStore's cloud path, the client writes the `optical_drafts` overlay + `optical_journal` + insert-only `operation_requests`, and (per the plan's event-bookkeeping decision) `optical_order_events` + the `optical_payments` ledger row. The synced `optical_customers`/`optical_orders` rows are left for the later sync task (as TodoStore never writes `server_todos`).
- Refund is an append-only ledger entry (distinct negative-amount entry in the client body so the balance recomputes correctly); no negative synced `optical_payments` row is written (migration 016 enforces `amount_paisa > 0`), deferring the backend refund-row shape to the sync task.
