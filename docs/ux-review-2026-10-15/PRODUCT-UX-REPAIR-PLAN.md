# Raheel Optics product UX repair plan

Restored from the agreed conversation plan because the previously linked local-commerce file is absent. Authoritative source is `/home/saad/Code/raheel-optics`. Read DEVICE-UX-REVIEW.md for actual connected-phone observations and screenshots.

## Domain and persistence first

Persist customers independently of orders/history, with stable IDs and independent revisions. Normalize Pakistani mobile numbers to +92 while preserving display input. Atomically create a customer with their first order; known numbers offer reuse. Keep historical order prescriptions intact when shared contact details/latest prescription change. Queue offline operations idempotently and connect optical tables to the actual app write/sync path.

Statuses: New, In production, Ready for pickup, Delivered, Cancelled. Active means New + In production. Ready must remain separate from Delivered. Store money as integer paisa and maintain a payment ledger and activity events.

## Orders and editor

Compact shop/date header and one row of Active/Ready/Overdue counts. Rounded search by name, normalized full/partial mobile or order number. Filters by status, order date, promised date, payment state and overdue; labeled sort dropdown for newest, oldest, promised date, customer name and outstanding balance. Apply to all records before pagination. Remove todo priority.

Cards include customer, phone, order number, frame thumbnail, dates, status, amount/balance and reminder. Tap opens dedicated Order Detail. Right swipe offers readiness confirmation and Undo. Detail supports editing, payments, production/readiness/delivery/cancellation, reminders, repeat order and WhatsApp drafts.

Use a full-screen New/Edit Order form with persistent Save. Include customer/contact, structured OD/OS distance prescription, optional near/ADD/PD, lens/frame choices, photo, payment, editable order date (now by default), optional promised date, reminders and notes. Keep SPH visible; + measurement reveals optional CYL/AXIS and PRISM/BASE pairs with removal. Support signed values, Plano, AXIS 1–180 and non-negative prism. Preserve existing reminder offsets using promised time as anchor.

Frame images need camera/gallery preview, compression, durable offline retention/retry and private remote storage. Do not treat a transient URI as a complete upload pipeline.

## Customers

Search by name/mobile, filter by outstanding/new/returning, and show a labeled sort dropdown: Recently added, Recently ordered, A–Z, Z–A, Most orders, Highest spend, Balance highest. Counts/totals cover the full dataset.

Tap customer opens prefilled New Order with customer ID/name/numbers/latest prescription. Provide secondary customer details/history action. Clear frame image, previous frame-specific choices, payments, status, reminders and dates. Require copied-prescription review. Saving profile edits updates the shared customer; old order prescription snapshots stay unchanged. Detect phone collisions rather than silently merging customers.

## Analytics

Show Active, Ready for pickup and Delivered rather than priority/deleted-task charts. Periods: Today, Week, Month, Quarter, Custom. Label current backlog versus period events. Sales excludes cancelled totals; Collected comes from payment transactions; Outstanding reconciles charges/payments/refunds. Include order count, average value, new/returning customers and trends once full-dataset aggregation is correct. Do not sum only visible pages.

## Settings, hours and storage

Dedicated shop screen for name/contact/address. Manual address and optional current-location lookup with pin/address confirmation, editable result and permission/GPS/failure fallback. Store coordinates separately.

Seven independently open/closed days including Sunday, time pickers, multiple intervals, copy-to-selected-days and special-date exceptions. Validate overlaps/overnight periods in Asia/Karachi. WhatsApp draft uses effective shop hours or a one-time pickup window, optional balance, and closed-shop contact fallback. Record share opened, never assume delivery.

Replace task imports with Active Orders and Customers plus combined versioned optical backup. Preview errors/duplicates/counts, import customers before linked orders, preserve IDs/mapping, make retries idempotent, and avoid silently reactivating reminders. State whether images are included. Cleanup preserves customers, active/unpaid orders and unsynced work.

## Delivery and acceptance cycle

1. Domain storage/customer upsert/statuses/migrations/optical sync.
2. Compact Orders/search/filter/sort, full editor and Order Detail.
3. Customer directory/repeat order/shared profile editing.
4. Shop hours/location, images/reminders/WhatsApp, optical import/export.
5. Reconciled analytics, terminology/branding/accessibility, then device acceptance.

Use labeled demo records and do not send real customer messages. On phone: verify onboarding/navigation/themes/keyboard; manual and optional detected address; Sunday/lunch break/special closure persistence; create new customer/order with both-eye optional fields/photo/partial payment/dates/reminder; confirm immediate customer appearance; test formatted/local/+92/partial search and combined filters/sorts; repeat order and shared profile editing with historical prescription preservation; validate inputs; production→ready→undo→ready→delivered; inspect WhatsApp draft without sending; reconcile financial/status/period counters; preview/retry imports; create/edit offline, restart/reconnect and verify no duplicates or lost images/fields; test short reminder and notification permission. Record APK/source identity, screenshots, expected/actual behavior, logs and remaining limits. Build success alone is not acceptance.
