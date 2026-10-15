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
    Table.localOnly("analytics_cache", listOf(Column.text("body"))),
    Table.localOnly("sync_checkpoint", listOf(Column.integer("revision"), Column.text("todo_id"))),
    Table.localOnly("drafts", listOf(Column.text("body"), Column.text("operation_id"))),
    Table.localOnly("local_totals", listOf(Column.integer("active"), Column.integer("completed"), Column.integer("deleted"), Column.integer("revision"))),
    Table.localOnly("local_state", listOf(Column.text("status"), Column.integer("revision"))),
    Table.localOnly("journal", listOf(Column.text("payload"), Column.text("state"), Column.integer("ack_revision"), Column.text("message"), Column.integer("created_at"))),
    Table.localOnly("history_cache", listOf(Column.text("body"), Column.integer("occurred_at"), Column.text("state"))),
    Table.localOnly("inbox", listOf(Column.text("todo_id"), Column.text("title"), Column.integer("time"), Column.integer("read"))),
    Table.localOnly("deadline_receipts", listOf(Column.text("todo_id"), Column.integer("deadline_at"))),
    Table.localOnly("reminder_receipts", listOf(Column.text("todo_id"), Column.integer("reminder_at"))),
))
