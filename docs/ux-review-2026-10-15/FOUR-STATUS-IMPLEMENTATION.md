# Four statuses and home revision

Canonical Kotlin enum: ACTIVE / READY_FOR_PICKUP / COMPLETE / CANCEL. Display labels: Active / Ready for pickup / Complete / Cancel. Old enum values decode using aliases; writes use canonical values. Legacy todo storage/operation flags remain internal transport compatibility fields, not additional optical statuses.

Updated defaults, lifecycle transitions, list filters, full stored-record counters, card labels/colors, eligibility for readiness, cancellation confirmation, completion action, onboarding text and analytics status chart. Removed analytics priority controls/chart. Terminal local orders are included from their latest history snapshot and remain filterable after restart. Completed/cancelled cards do not show active overdue timers or editing/readiness/cancellation actions.

Home now has compact date context, search, wrapping status filters/counts and a single list toolbar with Filter and Sort. No hero or summary-card block. All four status choices remain visible at the tested phone width.

Validation: debug build and unit suite passed; new tests cover enum aliases, labels and transitions. Device OrderStatusStoreTest passed (1 test, zero failures/errors): create four states, restart database, verify filters and counts. Final UI-only wrapping adjustment rebuilt successfully. Installed and visually reviewed on connected 2404ARN45A. Screenshot: four-status-home.png.

Backend migration 017 prepares the four PostgreSQL enum values, merges new/in_production into active, and maps old statuses without deleting orders. Updated backend SQL fixture. These SQL changes have not been executed or deployed; live cloud synchronization is not certified by this change. Local/cloud history retention still limits historical rows available on-device pending the independent optical persistence work.
