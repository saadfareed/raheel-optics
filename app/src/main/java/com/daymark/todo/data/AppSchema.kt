package com.daymark.todo.data

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

val appSchema = Schema(listOf(
    Table("active_todos", listOf(Column.text("body"), Column.integer("revision"))),
    Table("user_totals", listOf(Column.integer("active"), Column.integer("completed"), Column.integer("deleted"), Column.integer("revision"))),
    Table.insertOnly("operation_requests", listOf(Column.text("payload"))),
    Table.localOnly("server_todos", listOf(Column.text("body"), Column.integer("account_revision"))),
    Table.localOnly("server_totals", listOf(Column.integer("active"), Column.integer("completed"), Column.integer("deleted"), Column.integer("revision"))),
    Table.localOnly("quarantined_changes", listOf(Column.text("sdk_data"), Column.integer("transaction_id"), Column.text("operation_id"), Column.text("message"), Column.integer("created_at"))),
    Table.localOnly("upload_pause", listOf(Column.text("body"))),
    Table.localOnly("import_receipts", listOf(Column.integer("created_at"))),
    Table.localOnly("resource_limits", listOf(Column.text("body"), Column.integer("received_at"))),
    Table.localOnly("sync_checkpoint", listOf(Column.integer("revision"), Column.text("todo_id"))),
    Table.localOnly("drafts", listOf(Column.text("body"), Column.text("operation_id"))),
    Table.localOnly("local_totals", listOf(Column.integer("active"), Column.integer("completed"), Column.integer("deleted"), Column.integer("revision"))),
    Table.localOnly("local_state", listOf(Column.text("status"), Column.integer("revision"))),
    Table.localOnly("journal", listOf(Column.text("payload"), Column.text("state"), Column.integer("ack_revision"), Column.text("message"), Column.integer("created_at"))),
    Table.localOnly("history_cache", listOf(Column.text("body"), Column.integer("occurred_at"), Column.text("state"))),
    Table.localOnly("inbox", listOf(Column.text("todo_id"), Column.text("title"), Column.integer("time"), Column.integer("read"))),
    Table.localOnly("deadline_receipts", listOf(Column.text("todo_id"), Column.integer("deadline_at"))),
    Table.localOnly("reminder_receipts", listOf(Column.text("todo_id"), Column.integer("reminder_at"))),
    Table("optical_customers", listOf(Column.text("name"), Column.text("mobile"), Column.text("normalized_mobile"), Column.text("alternate_mobile"), Column.text("notes"), Column.integer("created_at"), Column.integer("updated_at"), Column.integer("revision"),Column.text("latest_prescription"),Column.text("previous_mobiles"))),
    Table.localOnly("optical_customer_cache",listOf(Column.text("body"))),
    Table("optical_orders", listOf(Column.text("customer_id"), Column.text("order_number"), Column.text("status"), Column.text("prescription"), Column.text("frame"), Column.text("lens"), Column.integer("frame_paisa"), Column.integer("lens_paisa"), Column.integer("extras_paisa"), Column.integer("discount_paisa"), Column.integer("tax_paisa"), Column.integer("total_paisa"), Column.integer("ordered_at"), Column.integer("promised_at"), Column.integer("reminder_at"), Column.integer("delivered_at"), Column.text("notes"), Column.integer("revision"), Column.integer("updated_at"))),
    Table("optical_payments", listOf(Column.text("order_id"), Column.integer("amount_paisa"), Column.text("method"), Column.integer("received_at"), Column.text("note"), Column.integer("created_at"))),
    Table.localOnly("optical_drafts", listOf(Column.text("body"), Column.text("operation_id"))),
    Table.localOnly("optical_journal", listOf(Column.text("payload"), Column.text("state"), Column.integer("ack_revision"), Column.text("message"), Column.integer("created_at"))),
))
