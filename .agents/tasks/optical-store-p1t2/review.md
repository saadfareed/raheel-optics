# OpticalStore client write path with idempotent op-queue

OpticalStore adds the client-side write path for the optical domain, built as a direct parallel to TodoStore's cloud op-queue. Every mutation runs in one `db.writeTransaction` guarded by a `writes` Mutex and an `admit()` precondition check, emitting the three-row idempotent envelope keyed on the `OpticalOperation.id`: an `optical_journal` row (`state='queued'`), an `optical_drafts` overlay row, and an insert-only `operation_requests` row. All eight operations are present, each state-changing op writes an `optical_order_events` bookkeeping row with the migration-016 event type, and a `snapshot()` read helper overlays drafts onto the (empty-in-Task-2) synced tables, computing per-order balances and customer↔order linkage. The cutover is clean: the diff adds exactly two files (`OpticalStore.kt`, `OpticalStoreTest.kt`) and touches nothing in the todo path or backend.

Watch for: refunds write no synced ledger row (likely) — the later sync task must reconstruct them from the `payment_refunded` event plus the draft body. No blocking violations found against the task requirements.

**Verdict**: APPROVED

## High-level view

The write envelope mirrors TodoStore `save` faithfully — same Mutex + writeTransaction + `admit` + journal/drafts/operation_requests triple, same op construction (fresh UUID id, `baseRevision`, `previousOperation`, `occurredAt`, `beforeStatus`), same `recordResult`/`reconcile` shape. The `localOnly` branch is correctly dropped (optical is cloud-only in Task 2) and the shared `CachePolicy`/`AdmissionPolicy`/`StorageStats` classes are reused rather than redeclared, confirmed by the two-file diff.

Customer identity is keyed by normalized mobile resolved drafts-first then synced. `order_create` resolves with `reuse=true` so a known number silently reuses the existing customer id (no duplicate), while `upsertCustomer` uses `reuse=false` so a number owned by a different id throws the catchable `PhoneCollisionException` carrying both ids. This is the distinct, non-silent outcome the task requires.

The payment ledger is append-only: `addPayment` appends and writes a synced `optical_payments` row (`amount_paisa>0`), `refundPayment` appends a distinct negative-amount entry to the client body (no synced row, deferred to the sync task per migration 016's positivity constraint) so `paidPaisa` drops and the balance rises. Guards enforce payment `> 0`, payment `<= total`, and refund `<= paid`.

`total_paisa` is never persisted as an authoritative client column — the store writes only component amounts plus the ledger and treats `totalPaisa`/`paidPaisa`/`balancePaisa` as model-computed. Status stays UPPERCASE client-side with `@JsonNames` aliases untouched; `order_status` routes through `OpticalRules.transition` for the allowed matrix and terminal lock. Prescription snapshot preservation is structural: `upsertCustomer` writes only the customer draft and never rewrites any order body.

The eight required behaviors are tested as instrumented tests on a real device (PowerSync needs native libs + Context, matching how TodoStore's own store tests are structured). Verification evidence shows the 8 tests passing, `assembleDebug` and `testDebugUnitTest` green, and the app launching without a fatal crash.

<details>
<summary>Issues (1)</summary>

1. **Refund synced-row deferral (likely)** — refunds intentionally write no `optical_payments` row (migration 016 forbids `amount_paisa<=0`), so a refund is recoverable only from the `payment_refunded` event + draft body. The later sync task must reconstruct the backend refund-row shape from those, not from a synced ledger row.

</details>

<details>
<summary>Details</summary>

### Write envelope and TodoStore parity

The `write { tx -> ... }` wrapper takes the `writes` Mutex and runs the block in `db.writeTransaction`, then unwraps PowerSync's wrapping of thrown causes so a `PhoneCollisionException` / `IllegalArgumentException` / `IllegalStateException` surfaces to the caller instead of a wrapped message. This is a deliberate improvement over TodoStore's bare `check(...)`/`error(...)` guards and is exactly what makes the collision outcome catchable per requirement.

Each op is built the same way TodoStore builds its `Operation`: fresh `UUID` id, the typed `OpticalPayload` variant, the `OpticalActions` constant, `baseRevision` from the before-revision, `previousOperation` resolved from the current draft's `operation_id`, `occurredAt`, and `beforeStatus` (uppercase `status.name` or `"absent"`). `writeOp` inserts the journal row with a plain `INSERT` (throws on duplicate id, as in TodoStore) and the `operation_requests` row with `INSERT`; drafts use `INSERT OR REPLACE`. The user-facing methods generate a fresh op id per call so they never collide; only the replay path needs explicit idempotency.

`admit` mirrors TodoStore: free-space check, pending-op cap counted from `optical_journal` where `state IN ('queued','conflict')`, and for `order_create` an active-order cap against `admission.tasks`. The `localOnly` branch is correctly absent. The active-order count unions synced non-terminal orders with draft non-terminal orders; in Task 2 the synced side is always empty, so the check is driven entirely by drafts — internally consistent with the UPPERCASE status stored in draft bodies.

### Idempotent replay via `apply()`

The replay/sync entry point is `apply(op)`, which short-circuits when `alreadyApplied(tx, op.id)` finds an existing journal row — a clean no-op rather than a constraint error. The journal is used as the locally-queryable idempotency ledger because `operation_requests` is insert-only (not readable back), which is the correct choice given the PowerSync CRUD semantics. The two replay tests (`replayingSameOperationIdIsANoOp`, `offlineCreateThenReplayProducesNoDuplicates`) assert a single journal/drafts/event row after double-apply, locking this in. `apply` reconstructs the same per-payload side effects as the direct methods, including the synced `optical_payments` row for `payment_add` and the negative-ledger body mutation for `payment_refund`.

### Customer identity and phone-collision policy

`resolveCustomer` looks up an existing id for the normalized mobile in drafts first, then synced `optical_customers` (excluding ids already shadowed by a draft). With `reuse=false` (the `upsertCustomer` path), a number owned by a different id throws `PhoneCollisionException(existingId, attemptedId, normalizedMobile)`; with `reuse=true` (the `order_create` path) it silently adopts the existing id. `knownNumberReusesExistingCustomerId` confirms the second order reuses the first customer id and leaves a single customer draft; `phoneCollisionSurfacesDistinctOutcome` confirms the exception carries the right existing/attempted ids and creates no duplicate. No silent merge, no silent duplicate.

`createOrder` upserts the resolved customer and creates the order in one transaction, emitting a separate `customer_upsert` op only when the customer is new or changed (name/mobile/alternate/notes differ), so each op maps 1:1 to a payload variant and replays independently. The `created` event is written with `{"status": ...}`.

### Payment ledger and balance

```
addPayment:    paid += amount   (require amount>0, require paid+amount <= total)   synced optical_payments row + payment_added event
refundPayment: paid += (-amount) (require amount>0, require amount <= paid)         NO synced row + payment_refunded event
```

`paymentLedgerAccumulatesAndBalanceIsCorrect` walks the full path: total 1,000,000; two payments 400k+300k → paid 700k / balance 300k; an overpay of 400k is rejected as `IllegalArgumentException`; a 200k refund → paid 500k / balance 500k with three ledger entries. The ledger is never collapsed and the original payment is never deleted. The refund writes no synced `optical_payments` row because migration 016 enforces `amount_paisa>0`; the backend refund-row shape is explicitly deferred to the sync task. That deferral is the one thing the sync task must honor — refunds are only recoverable from the `payment_refunded` event plus the draft body, not from a synced ledger row.

### Status, totals, and derived fields

`changeStatus` runs `OpticalRules.transition`, which enforces the allowed matrix (ACTIVE→READY/COMPLETE/CANCEL, READY→COMPLETE/ACTIVE/CANCEL) and the terminal lock (COMPLETE/CANCEL → empty set), and sets `deliveredAt` on COMPLETE. Status is persisted UPPERCASE inside the serialized body; `OrderStatus`'s `@JsonNames` aliases (NEW/IN_PRODUCTION→ACTIVE, etc.) are untouched. The store never writes a `total_paisa` client value — only the component amounts and the ledger — so it cannot fight migration 016's generated `total_paisa` column. `totalPaisa`/`paidPaisa`/`balancePaisa` remain model-computed getters.

### Prescription snapshot preservation

`upsertCustomer` writes only the customer draft and never touches an order body, and `editOrder` edits only the order's own body (including its own embedded prescription) after an optimistic before-revision check. Because each `OpticalOrder` embeds its own `prescription`, editing the shared customer structurally cannot rewrite a prior order's snapshot. `editingCustomerLeavesPriorOrderPrescriptionSnapshotUnchanged` renames the shared customer and asserts the prior order keeps `distanceRight.sphere = "-1.25"` and `prescribedBy = "Dr A"` while the customer rename is visible. `editOrder`'s concurrency check (`current.revision == before.revision`, else "This order has changed") is the required before-revision guard.

### Snapshot read helper

`snapshot()` overlays draft customers/orders (split by the `kind` discriminator in the body JSON) onto synced rows with drafts winning on id collision, and reconstructs synced orders from their decomposed columns including synced payments. `snapshotOverlayShowsQueuedOrderWithBalanceAndCustomerLinkage` confirms a queued create shows with the right computed balance and the customer linked via `order.customer.id`. It is full-dataset (no pagination) — intended for tests/integration reads, with the UI page path left to a later task.

### Clean cutover

The diff is exactly two new files. Grep confirms OpticalStore reads only `optical_*` tables and the insert-only `operation_requests`; the only `todo`/`migrat` hits are comment references. `TodoStore.kt` and `AppSchema.kt` are unchanged (empty `git diff --stat`). No Todo→optical migration code, no read of `todos`/`active_todos`/`server_todos`. The shared policy data classes are reused, not redeclared.

### Test coverage

Eight instrumented tests cover all eight required behaviors on a real device: atomic customer+order creation, known-number reuse, idempotent replay, offline-create-then-replay, phone collision, payment ledger accumulation + overpay rejection + refund, snapshot overlay with balance/linkage, and prescription-snapshot preservation. Verification evidence records `OK (8 tests)`, `assembleDebug` BUILD SUCCESSFUL, `testDebugUnitTest` 95 tests green (no regressions), and the app launching without a FATAL EXCEPTION. The instrumented-vs-JVM placement matches TodoStore's own store tests (`OfflineStoreTest`, `OrderStatusStoreTest`), which require a real PowerSync DB. The runner workaround (manual `--no-streaming` install + `am instrument` after an `INSTALL_FAILED_USER_RESTRICTED`) ran the identical suite on the identical runner.

Not tested at the store layer: `recordResult`/`reconcile` (the sync-receipt path), `changeStatus` transition matrix (covered by `OpticalRules` pure-logic tests elsewhere), and `attachImage`/`removeImage` (bookkeeping-only in Task 2). These are acceptable gaps for this task's scope.

</details>

<details>
<summary>File map</summary>

- `app/src/main/java/com/daymark/todo/data/OpticalStore.kt` — new client write path: 8 operations, idempotent op-queue, `apply` replay, `snapshot`, `recordResult`, `reconcile`, `PhoneCollisionException`.
- `app/src/androidTest/java/com/daymark/todo/OpticalStoreTest.kt` — 8 instrumented tests, one per required behavior.

Full diff: `git -C /home/saad/Code/raheel-optics/.worktrees/optical-store diff main...HEAD`

</details>
