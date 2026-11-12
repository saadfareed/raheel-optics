# Customers directory and repeat-order UI — 2026-10-16

Source: /home/saad/Code/raheel-optics, main based on acc1593. An existing OpticalStore implementation on the optical-store worktree remains separate; no merge, sync protocol change, or competing profile table was introduced here.

Implemented:

- Customers no longer deduplicates the currently visible history page. SQL groups all locally available effective order snapshots by normalized mobile and pages the directory results.
- First active order creates a directory entry immediately. Latest prescription, order count, last order time and unpaid balance derive from locally stored records. Cancelled totals are excluded from balance.
- Name/normalized phone search, labeled sorting dropdown (recent order, recent addition, A–Z/Z–A, most orders, highest balance), balance-due toggle, and pagination.
- Customer tap opens New Order with a fresh UUID/order number/date and copied contact/prescription. Image/frame, lens choices, payments/pricing, status, notes, reminders, old dates and revisions reset. Copied prescription review message added. Saving preserves prescription fields that the current editor does not expose.

Validation: debug app and unit tests pass, including RepeatOrderTest verifying cleared fields and preservation of previous prescription. Actual directory SQL tested against SQLite using 28 orders and a second customer: correct grouping, latest record and payment-adjusted balance. CustomerDirectoryStoreTest added for phone search, sorting, balance filtering and grouping beyond an order page; compiled but not run on-device. Phone was absent from adb devices and no emulator was configured on 2026-10-16.

Still pending:

- Shared independently persisted customer profile/ID, customer edits and phone-collision handling must be wired through the existing OpticalStore work and its future sync integration. Editing repeat-order contact data currently changes the new order, not a shared profile. Do not claim profile synchronization is implemented.
- Directory is device-data scope, not complete cloud history. First added date currently means earliest stored order, not independent profile created_at. History cleanup can remove historical source records until independent persistence is integrated.
- Live UI/install testing, customer detail/profile editing, prescription-history screen, and known-number reuse prompt remain pending.

Do not deploy this as the complete Customers milestone; it is the directory and repeat-form slice.
