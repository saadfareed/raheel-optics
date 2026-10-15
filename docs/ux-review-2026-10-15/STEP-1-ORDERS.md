# Step 1: Orders layout and list controls

Implemented in /home/saad/Code/raheel-optics on 2026-10-15.

- Removed the tall hero/Order desk stack; compact date/storage context and Open/Today/Overdue metrics use existing full-store counters. Open currently includes ready orders; separate Active/Ready counters require the upcoming status aggregation work.
- Rounded search accepts text keyboard input, matches customer/order numbers, and normalizes Pakistani phone searches against saved normalized numbers. Escapes SQL LIKE wildcards.
- Separate Sort dropdown: newest/oldest by order date, promised soonest/latest, recently updated. SQL ordering and cursor keys updated together.
- Filter sheet replaces urgency with New/In production/Ready status and promised-date choices. Preserves search/sort on apply. Clear action resets active search and filters.
- Filter sheet opens fully and respects Android bottom navigation; discovered and fixed on device.

Validation: :app:testDebugUnitTest and :app:assembleDebug passed. Added OrderListRulesTest for local/international/formatted/partial phone queries and order date versus edit date sorting. Rebuilt after the final sheet-inset change. Installed final debug APK on model 2404ARN45A and verified Orders layout, reachable Apply filters and Sort dropdown options in guest mode.

Limits: No populated multi-page data/cloud sync acceptance run yet. Payment/date-range filters and alphabetical/balance sorting are still pending. Customers, shared profiles, editor/detail, analytics and shop-hours repairs remain separate upcoming steps. This is the first implementation slice, not completion of the repair plan.
