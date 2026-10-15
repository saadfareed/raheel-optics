# Raheel Optics device review — 2026-10-15

Authoritative source: `/home/saad/Code/raheel-optics`, as explicitly confirmed by the owner. Do not reconcile or replace it with the older local-commerce copy. The source currently has no `.git` directory; this review does not initialize Git or alter remote history.

Reference: PRODUCT-UX-REPAIR-PLAN.md, copied alongside this review. Its earlier assumption that the local-commerce clone is authoritative is superseded by this source decision.

Built this source successfully with `:app:assembleDebug`, installed it on connected model 2404ARN45A, and launched package pk.raheeloptics.orders. Reviewed first-run onboarding, guest entry, Orders, Customers, Analytics, Settings, shop details/hours dialog, and the expanded New Order editor. No real account was accessed and no customer/order or shop settings were saved. This is a UI overview, not a completed order lifecycle or cloud-sync acceptance test.

## Device observations and decisions

1. Orders: hero plus Order desk pushes search near the bottom and the order list below the initial viewport. Replace both with compact date/shop context and a single row of Active / Ready / Overdue counts. Keep search, Filters, labeled Sort dropdown, and the first order visible where screen/font size permits. Use quieter surfaces rather than large saturated panels for zero-count metrics.
2. Search: current rectangular outlined field has a floating label and competes with two large summary panels. Use a rounded, single-line search surface, placeholder Name, mobile or order number, clear action, and consistent margins. Normalize mobile queries and apply search before pagination.
3. Customers: duplicated page title; no search; Newest chip toggles rather than opening options. Remove redundant title, add search, useful filters, and a proper dropdown. Use independently stored customers, not deduplicated history pages. Tapping a customer opens a repeat order; provide a secondary View customer details action.
4. Repeat orders: retain customer ID, contact data, and latest prescription; require review. Reset image, payments, status, reminders, old dates, and frame-specific values. Saving contact/latest prescription edits updates the customer profile; preserve historical order prescriptions.
5. Analytics: UI displays Active / Completed / Deleted and Open orders by priority. Replace with Active (New + In production), Ready for pickup, Delivered; show Cancelled separately if useful. Remove priority. Existing developer-facing text about backend migrations should be removed from shopkeeper screens. Show period controls and only trustworthy full-dataset financial totals.
6. Shop settings: narrow modal contains address plus free-text Monday–Saturday and Sunday. Sunday is editable text, but does not have explicit open/closed behavior. Replace with a scrolling screen: shop details, manual/current-location address confirmation, and seven independent day schedules with switches, time pickers, split intervals, and special-day exceptions. Generate pickup messages from effective hours.
7. Order editor: opens half-height, initially showing customer fields and almost no prescription. Expanded form has large vertical gaps; Save is not visible at the beginning. Use a full-screen form with a fixed top bar and persistent Save action. Group Customer, Prescription, Frame/lens, Payment, Dates/reminders; expand advanced fields on demand. Preserve accessible touch sizes while reducing unnecessary card padding.
8. Prescription: visible SPH with + Optional per eye is a useful starting point. Rename action to + Add measurement. Keep CYL→AXIS and PRISM→BASE pairing, support removal, signed typed values and Plano, and retain structured values.
9. Branding: Raheel logo is present in splash/header/onboarding. Dark blue lettering has weak contrast on the dark splash/header and the small wordmark repeats the shop name beside it. Prefer an eye-only header mark and a contrast-safe splash treatment; verify both themes.
10. Navigation: keep five destinations and prominent center Add. Ensure initial New Order opens fully rather than requiring discovery of a drag gesture. Notification inbox remains accessible from Orders.

## Implementation order and acceptance

Follow the repair plan for customer/order storage, status transitions, and optical sync first; then Orders/Customers and editor/detail flows; then shop hours/location, import/export, and reconciled analytics. Current screen work must not merely relabel todo counters.

After implementation run the plan's full demo cycle: create customer+order, partial payment and image, phone-format search, repeat order with profile edits, production→ready→delivered with undo, WhatsApp draft without sending, payment/analytics reconciliation, Sunday hours, import retry, reminder, offline restart and sync. Capture expected/actual outcomes. Account sync, payment correctness, image recovery and reminders remain unverified by this overview.

Screenshots captured during review: /tmp/optics-orders.png, /tmp/optics-customers.png, /tmp/optics-analytics.png, /tmp/optics-shop.png, /tmp/optics-order-expanded.png. Initial /tmp/optics-new-order.png captured an earlier navigation state and should not be used as editor evidence.
