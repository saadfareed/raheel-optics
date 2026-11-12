package pk.raheeloptics.orders.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** A bounded preview. History and queued operations are never imported. */
enum class OpticalExportSelection(val label:String) {ACTIVE_ORDERS("Active orders"),CUSTOMERS("Customers"),BACKUP("Orders and customers backup")}
data class TaskImportPreview(val activeTasks: List<Order>, val skippedNonActive: Int, val otherRecords: Int,val customers:List<Customer> = emptyList())

object TaskImport {
    private const val MAX_LINE = 256 * 1024
    private const val MAX_BYTES = 128 * 1024 * 1024L
    private const val MAX_RECORDS = 100_000
    private const val MAX_TASKS = 500
    private val json = Json { ignoreUnknownKeys = true }
    private val types = setOf("task", "history", "operation", "localState", "quarantinedChange", "queuedChange","customer","customerOperation")

    fun copyId(accountId: String, sourceId: String): String = UUID.nameUUIDFromBytes(
        "daymark-import-v1:$accountId:$sourceId".toByteArray(Charsets.UTF_8)).toString()

    fun preview(input: InputStream): TaskImportPreview {
        val source = input.buffered()
        val line = ByteArrayOutputStream()
        var total = 0L
        var records = 0
        var header = false
        var skipped = 0
        var other = 0
        val tasks = ArrayList<Order>()
        val seen = HashSet<String>()
        val customers=ArrayList<Customer>()
        val customerPhones=HashSet<String>()
        fun parseLine() {
            if(line.size()==0) return
            val decoded=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString()
            line.reset()
            val row=json.parseToJsonElement(decoded) as? JsonObject ?: error("Invalid export record.")
            if(!header) {
                val format=row["format"]?.jsonPrimitive?.content;val version=row["version"]?.jsonPrimitive?.content
                check((format=="daymark-export" && version=="1") || (format=="raheel-optics-export" && version=="2")) {"Unsupported optical export."}
                header=true
                return
            }
            records++
            check(records<=MAX_RECORDS) {"Export has too many records."}
            val type=row["type"]?.jsonPrimitive?.content
            check(type in types && row["data"]!=null) {"Unexpected export record."}
            if(type=="customer") {
                val customer=json.decodeFromJsonElement<Customer>(row.getValue("data"))
                UUID.fromString(customer.id);OpticalRules.validateCustomer(customer)
                check(customerPhones.add(customer.normalizedMobile)) {"Export contains duplicate customer phone numbers."}
                check(customers.size<MAX_TASKS) {"Import supports at most 500 customers at a time."}
                customers+=customer
            } else if(type=="task") {
                val todo=json.decodeFromJsonElement<Order>(row.getValue("data"))
                UUID.fromString(todo.id)
                check(seen.add(todo.id)) {"Export contains a duplicate task."}
                Rules.validate(todo.copy(reminderAt=null),System.currentTimeMillis(),checkReminder=false,checkDeadline=false)
                if(todo.status=="active") {
                    check(tasks.size<MAX_TASKS) {"Import supports at most 500 active tasks at a time."}
                    tasks.add(todo)
                } else {
                    check(todo.status=="completed" || todo.status=="deleted") {"Export has an invalid task status."}
                    skipped++
                }
            } else other++
        }
        while(true) {
            val byte=source.read()
            if(byte<0) break
            total++
            check(total<=MAX_BYTES) {"Export is too large to inspect."}
            if(byte==10) parseLine() else {
                check(line.size()<MAX_LINE) {"An export record is too large."}
                line.write(byte)
            }
        }
        parseLine()
        check(header) {"This is not a supported optical export."}
        return TaskImportPreview(tasks,skipped,other,customers)
    }
}
