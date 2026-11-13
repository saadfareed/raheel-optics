# Optical order API (migration 020) — contract for the Android client

Status: applied to Supabase (migrations 020-023). Checked by scripts/backend-gate.sh and scripts/wire-contract-check.sh.
All functions require an authenticated user and are called as Supabase RPCs (`/rest/v1/rpc/<name>`).
Money is integer paisa. Times are epoch milliseconds. Status values are `ACTIVE`, `READY_FOR_PICKUP`,
`COMPLETE`, `CANCEL`. Payment methods: `CASH, CARD, BANK_TRANSFER, JAZZCASH, EASYPAISA, OTHER` (any case on input).

## Write: `apply_optical_order(payload jsonb) -> jsonb`
```json
{ "id": "<operation uuid>", "action": "order_create | order_edit", "baseRevision": 0, "occurredAt": 1790000000000,
  "order": { "id": "<uuid>", "customerId": "<uuid>", "orderNumber": "RO-0001", "status": "ACTIVE",
    "orderedAt": 0, "promisedAt": null, "reminderAt": null, "deliveredAt": null, "notes": "",
    "prescription": { "distanceRight": { "sphere": "-2.00", "cylinder": "-0.50", "axis": "90" } },
    "frame": {}, "lens": {},
    "amounts": { "framePaisa": 0, "lensPaisa": 0, "extrasPaisa": 0, "discountPaisa": 0, "taxPaisa": 0 },
    "payments": [ { "id": "<uuid>", "amountPaisa": 70000, "method": "CARD", "receivedAt": 0, "note": "" } ] } }
```
- `order_create` needs `baseRevision: 0`; `order_edit` needs the order's current revision.
- `payments` is the FULL ledger. A negative `amountPaisa` is a refund. Existing entries must be sent back unchanged;
  new entries are appended. Changing or omitting an existing entry is rejected.
- Response `{accepted: true, order: {...}}` or `{accepted: false, message, order}` (business conflict: stale revision,
  duplicate order number, illegal status change, terminal-order edit, ledger change). Replay of the same operation id
  with the same payload returns the stored result; the same id with a different payload raises.
- Raises (client should quarantine, not retry): invalid fields, unknown customer, payment over total / refund over paid,
  `42501` not owned, `PT413` too large, `PT429` account order limit.
- Status rules: ACTIVE ↔ READY_FOR_PICKUP; either → COMPLETE or CANCEL; COMPLETE accepts only new payments; CANCEL is final.
- `order` in responses: `id, customerId, orderNumber, status, prescription, frame, lens, amounts, totalPaisa, paidPaisa,
  balancePaisa, orderedAt, promisedAt, reminderAt, deliveredAt, notes, revision, updatedAt, payments[]`.

## Read
- `optical_orders_page(search, statuses text[], payment_state 'unpaid|partial|paid', ordered_from, ordered_to,
  promised_from, promised_to, overdue_only, sort_key, page_offset, page_size<=100) -> {total, offset, rows[]}`
  `sort_key`: `created_desc|created_asc|updated_desc|promised_asc|name_asc|name_desc|balance_desc`.
  `search` matches customer name, order number, and mobile (any local/+92/formatted/partial form, >=3 digits).
  Rows: id, orderNumber, status, customer{id,name,mobile}, dates, totalPaisa, paidPaisa, balancePaisa, revision, updatedAt.
- `optical_order_detail(order_id) -> order + events[]` (null if not yours).
- `optical_analytics(from_ms, to_ms) -> {salesPaisa, ordersCount, averageOrderPaisa, collectedPaisa, refundedPaisa,
  netCollectedPaisa, outstandingPaisa, statusCounts{active,readyForPickup,complete,cancelled}, newCustomers,
  returningCustomers}` over `[from_ms, to_ms)`; sales exclude cancelled; outstanding and statusCounts are current.
- `optical_orders_api_version() -> 2` (migration 021 adds the per-action writes below).

## Per-action writes (migration 021)
Both apply to the order's CURRENT revision (deltas, not overwrites); the status machine, payment-total checks and
ledger rules of `apply_optical_order` still apply. Same replay/receipt semantics: same id + same payload returns the
stored result, same id + different payload raises. Unknown or foreign order -> `accepted:false` ("Order not found on the server.").
- `optical_order_status({id, action:"order_status", occurredAt, orderId, status, deliveredAt?}) -> {accepted, order|message}`
- `optical_payment_add({id, action:"payment_add"|"payment_refund", occurredAt, orderId, payment:{id, amountPaisa>0, method, receivedAt, note}})`
  `-> {accepted, order|message}`. `amountPaisa` is always positive; `payment_refund` is signed by the server.

## Customers (migration 018, unchanged)
`upsert_optical_customer(payload)` and `optical_customer_page(after_id)`.

## Client work this unlocks
1. Route `order_create`/`order_edit` to `apply_optical_order`, `order_status` to `optical_order_status`, `payment_add`/`payment_refund` to `optical_payment_add`; keep the existing queue/receipt handling.
2. Replace per-device list/search/analytics with the read functions when online; keep local fallback offline.
3. Stop writing orders as todos once queued legacy operations have drained.

## Download feed (migration 022)
`optical_order_feed(after_updated, after_id, page_size<=200) -> {rows[], has_more, totals}`
- rows: every order of the caller changed after the cursor, oldest first, in the same shape as `order` above plus
  `customer{id,name,mobile,normalizedMobile}`. The device stores the last row's `(updatedAt, id)` as its cursor.
- `updatedAt` is strictly increasing per owner (trigger), so a cursor never skips a change.
- totals: `{active (active + ready), completed, deleted (cancelled), revision (latest updatedAt)}`.
- `optical_orders_api_version() -> 3`. The app requires 3.

## App sync (current)
Every queued order change carries the full order and is sent to `apply_optical_order` (create when it has never been
confirmed, otherwise edit with the confirmed revision). Customer changes go to `upsert_optical_customer` first (FIFO).
Orders are then downloaded from `optical_order_feed`. Frame photos stay on the phone that took them.

## Retiring the to-do era
- 022 copies orders stored in `todos` into `optical_*` once (`optical_backfill_from_todos`, service role only, idempotent).
- 023 drops the to-do functions and moves the to-do tables to the private `legacy` schema (kept, not exposed).
  Drop `legacy` later when you no longer need it.
