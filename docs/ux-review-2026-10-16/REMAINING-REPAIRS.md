# Optical repair batch — 16 October 2026

Source: `/home/saad/Code/raheel-optics`. No live backend migration or deployment was performed.

## Implemented

- Dedicated full-screen order details: customer, distance/near prescription, frame preview, lens/frame information, amounts, dated payment/refund entries, repeat order and WhatsApp draft.
- Full-screen order editor with persistent save action; current/editable order timestamp; optional SPH, CYL/AXIS and PRISM/BASE; near and ADD/PD; frame/lens/extras/discount/tax; payment edits append a delta instead of rewriting earlier receipts.
- Gallery/camera frame selection copies a resized, EXIF-oriented image to app-private storage. Picker access is not required after import.
- Existing-number lookup offers saved customer details explicitly. New-number orders create shared profiles; profile updates do not rewrite old prescriptions.
- Ready swipe confirmation and Undo; only Active, Ready for pickup, Complete and Cancel are presented.
- Order sorting by creation/update/promised date, customer name and balance. Payment, order-date and promised-date filters; customer new/returning filter and spend sorting.
- Device-wide daily/weekly/monthly/quarterly/custom analytics, separately counting booked sales, actual dated collections/refunds, outstanding balance, order counts and four current status counts.
- Seven independent shop-day switches, split/overnight intervals, special-date overrides, manual address and explicit permission-based current location with pin/address review. Sunday is independently editable.
- Editable WhatsApp pickup draft uses shop hours, optional one-time pickup window, balance and closed-shop contact fallback. Opening a draft is not treated as delivery.
- Optical JSONL active-order/customer export and bounded preview/import; deterministic import IDs; existing customer profiles are preserved. Photo files are explicitly not included.
- Removed local history cleanup from Settings to protect completed-order records and analytics.
- Backend migration 019 preserves optical fields through the compatibility endpoint, supports completed-order payment edits without reopening, keeps revision/idempotency/admission protection, and advertises a compatibility version.
- The client refuses optical order uploads to an older server rather than accepting a receipt that silently drops optical fields. Queued local work stays intact.

## Verification

- Unit tests: 101 passed before the final rebuild, including five new shop-hours/payment/analytics tests.
- Local PostgreSQL 18: migrations 001–019 applied to a new isolated database. Optical payload preservation/late payments, legacy operations, validation, resource admission, history projections, optical financial/RLS and shared-customer RPC suites passed. The older optical fixture now uses the customer RPC instead of revoked direct customer writes.
- Final Android debug APK/device-test APK build and all 101 unit tests passed, including the change that leaves AXIS/BASE unset until explicitly chosen.
- Connected Android 16 phone: 18 isolated database tests passed (optical repairs, four statuses, customer directory/shared profiles, optical domain and offline storage). These tests did not clear or modify the owner's workspace.
- After unlocking the phone, all three Compose UI tests passed: fixed Save with optional prescription fields omitted, independent Sunday hours, and customer-profile editing. Editor/settings tests were repeated with the notification shade closed; screenshots were visually reviewed. Total: 21 distinct passing device tests (18 database + 3 UI).

## Remaining rollout boundaries

- Confirm an isolated Raheel Optics cloud project and authorize migration deployment before cloud rollout; the original todo application's live backend must not be modified accidentally.
- Cross-device frame-image upload/download is not implemented by this batch. Images are durable on the source device only, and JSONL exports omit image files.
- Analytics explicitly covers orders stored on this device, not uncached cloud history.
- Imports restore active orders/customers as copies, not terminal history or queued operations.
- Actual external-camera, denied/current-location and WhatsApp delivery need a human acceptance check; opening a message draft cannot verify customer delivery.
- A draggable map picker and full order-edit activity timeline are not implemented.

No generated build output, credentials, user databases or photo files should be staged or committed. Existing unrelated changes remain untouched.
