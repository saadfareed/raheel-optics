# Shared customer profiles — 2026-10-16

Source: `/home/saad/Code/raheel-optics`. Integrated the existing OpticalStore implementation into the working source and extended it for customer-profile integration; the original optical-store worktree was not changed or discarded.

## Behavior implemented

- Saving an order and its customer profile now happens in the same SQLite transaction. Known phone numbers reuse the profile ID; repeat drafts preserve that ID. Profile and order revisions remain independent.
- Shared profiles hold current contact details, customer notes, latest structured prescription and previous normalized phone numbers. Phone changes retain order linkage; collisions fail instead of silently merging customers.
- Customers projects current profile values over the directory view only. Previous order prescription snapshots are not rewritten. Customers with no remaining locally stored order history remain visible through independent profiles.
- Customer Details edits name, phone, notes, OD/OS measurements, ADD and PD. Repeat-order saves update the same shared profile. Existing non-edited prescription fields are preserved.
- Profile changes have a durable optical journal. Guest changes stay local; signed-in profile operations are dispatched to their own RPC rather than parsed/quarantined as todo operations. Unsupported future optical order commands are retained rather than quarantined.
- Profile cloud cache and owner-scoped paged refresh support independent customer reads. Acknowledgements remove only the corresponding draft, preserving newer queued edits. Missing backend migration retains the queued operation for retry.
- Stale/collision rejections remain visible in Customers. Users can review the cloud version, retry saved local changes, or correct details and save a rebased operation. Superseded queued operations are acknowledged locally without upload.
- Device exports now include customer profiles and optical journal records. Importing those new sections remains part of the pending optical import/export milestone.

## Backend

Migration `018_shared_customer_profiles.sql` adds prescription/phone-history fields, unique owner+phone identity, owner-isolated idempotency receipts, revision-checked profile upsert, and an owner-scoped paged reader. Direct authenticated profile writes are revoked so writes use the RPC. Mutations do not update historical order prescriptions.

Validated locally on an isolated PostgreSQL 18 instance with migration 016, 017 and 018, then `backend/tests/shared-customer-profiles.sql`. Checks passed: create/replay, phone history, stale revision rejection, alias collision, historical prescription preservation, paged reading, cross-owner isolation and anonymous permission denial.

Migration 018 was NOT applied to any live Supabase project. Signed-in profile changes wait in the durable queue until the backend is deployed. This step does not certify full optical order/payment/image cloud synchronization; the existing dedicated optical order migration remains a separate milestone.

## Verification

- Debug APK build and unit test suite passed. An initial concurrent Gradle incremental-cache collision was resolved with a sequential rebuild; no source rollback was used.
- Installed app and test APK with `adb install -r`, preserving app data. Invoked instrumentation directly, without the Gradle runner's app uninstall cleanup.
- Connected model 2404ARN45A: **14 tests passed** across SharedCustomerTest (3), CustomerProfileUiTest (1), CustomerDirectoryStoreTest (1), OrderStatusStoreTest (1), OpticalStoreTest (8).
- UI test edited demo name, mobile and prescription, scrolled the lazy form to Save, and checked the saved profile. It rendered an isolated Compose form; it did not modify the user's session or shop records.
- Database tests used unique temporary databases and cleaned up only those files. Tests covered repeat identity, original prescription preservation, profile survival after history cleanup/restart, phone collisions, unavailable-migration retry, conflict rebase/use-cloud behavior, and OpticalStore regressions.
- `customer-profile-editor.png` shows the isolated demo profile form, visually inspected in dark mode.

Remaining product milestones include the new full-screen order/detail flow, complete optical order sync and guest-profile transfer, shop hours/location, optical import, and full-dataset revenue analytics. These are not implied complete by this customer-profile step.
