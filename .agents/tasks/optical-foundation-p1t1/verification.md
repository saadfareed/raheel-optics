# Verification — Optical Foundation, Phase 1 Task 1

All commands run from the worktree root
`/home/saad/Code/raheel-optics/.worktrees/optical-foundation` with
`JAVA_HOME=/home/saad/jdk`.

## 1. Unit tests — BUILD SUCCESSFUL, new tests executed

Command:

    JAVA_HOME=/home/saad/jdk ./gradlew :app:testDebugUnitTest --console=plain

Result: `BUILD SUCCESSFUL in 5s`. Suite total went from 85 → **95 tests** (10 new),
all passing. New test classes and their executed cases (from
`app/build/test-results/testDebugUnitTest/`):

`OpticalOperationTest` — `tests="8" skipped="0" failures="0" errors="0"`:
- customerUpsertRoundTrips
- orderCreateRoundTrips
- orderEditRoundTrips
- orderStatusRoundTrips
- paymentAddRoundTrips
- paymentRefundRoundTrips
- imageAttachRoundTrips
- imageRemoveRoundTrips

`OpticalTransitionTest` — `tests="2" skipped="0" failures="0" errors="0"`:
- everyAllowedTransitionSucceedsWithSideEffects (6 allowed transitions + side effects)
- everyDisallowedTransitionThrowsIncludingTerminalAndSelf (10 disallowed pairs, incl. all
  moves out of terminal COMPLETE/CANCEL and all self-transitions)

Aggregate check: `95 tests total` across `app/build/test-results/testDebugUnitTest/`.

Note: round-trip tests pin all defaulted timestamp/revision fields in the fixtures
(`occurredAt`, `createdAt`, `orderedAt`, etc.) because kotlinx `Json` does not encode
default values, so an unpinned default would be re-evaluated on decode and break data-class
equality. Pinning makes the equality assertions deterministic.

## 2. Debug build — BUILD SUCCESSFUL

Command:

    JAVA_HOME=/home/saad/jdk ./gradlew :app:assembleDebug --console=plain

Result: `BUILD SUCCESSFUL in 13s` — the todo path still compiles alongside the new optical
additions.

## 3. Grep guards

No Todo→optical migration code anywhere in the new optical files, and no `Todo` reference
in `OpticalOperation.kt`:

    grep -rniE "migrat|fromTodo|toOptical|todo.*optical|optical.*todo" \
      app/src/main/java/com/daymark/todo/core/OpticalOperation.kt \
      app/src/test/java/com/daymark/todo/OpticalOperationTest.kt \
      app/src/test/java/com/daymark/todo/OpticalTransitionTest.kt
    # -> NONE FOUND

    grep -n "Todo" app/src/main/java/com/daymark/todo/core/OpticalOperation.kt
    # -> NONE FOUND

Existing todo tables are still present in `AppSchema.kt` (none removed):

    grep -oE '"(active_todos|user_totals|operation_requests|server_todos|drafts|journal|inbox)"' \
      app/src/main/java/com/daymark/todo/data/AppSchema.kt
    # -> active_todos, user_totals, operation_requests, server_todos, drafts, journal, inbox

New optical tables added:

    grep -oE '"(optical_customers|optical_orders|optical_payments|optical_order_events|optical_drafts|optical_journal)"' \
      app/src/main/java/com/daymark/todo/data/AppSchema.kt
    # -> optical_customers, optical_orders, optical_payments, optical_order_events,
    #    optical_drafts, optical_journal

## 4. Changed source files (only these)

    git -C <worktree> status --short -- app/src
     M app/src/main/java/com/daymark/todo/data/AppSchema.kt
    ?? app/src/main/java/com/daymark/todo/core/OpticalOperation.kt
    ?? app/src/test/java/com/daymark/todo/OpticalOperationTest.kt
    ?? app/src/test/java/com/daymark/todo/OpticalTransitionTest.kt

No changes to UI, ViewModel, TodoStore, synchronizer, or backend. No legacy code deleted.
No Todo→optical data-migration code added.
