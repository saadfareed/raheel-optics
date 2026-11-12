package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.TaskImport
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class TaskImportTest {
    private val activeId="11111111-1111-4111-8111-111111111111"
    private val doneId="22222222-2222-4222-8222-222222222222"
    @Test fun previewsOnlyActiveTasksAndKeepsImportIdsStable() {
        val export="""{"format":"daymark-export","version":1}
{"type":"task","data":{"id":"$activeId","title":"Keep me","notes":"private","status":"active"}}
{"type":"task","data":{"id":"$doneId","title":"Done","status":"completed"}}
{"type":"queuedChange","data":{"sdkData":"private raw operation"}}
""".trimIndent()+"\n"
        val preview=TaskImport.preview(ByteArrayInputStream(export.toByteArray()))
        assertEquals(listOf("Keep me"),preview.activeTasks.map {it.title})
        assertEquals(1,preview.skippedNonActive)
        assertEquals(1,preview.otherRecords)
        val first=TaskImport.copyId("account-a",activeId)
        assertEquals(first,TaskImport.copyId("account-a",activeId))
        assertNotEquals(activeId,first)
        assertNotEquals(first,TaskImport.copyId("account-b",activeId))
    }
    @Test fun rejectsDuplicateOrOversizedTaskBeforeWriting() {
        val task="""{"type":"task","data":{"id":"$activeId","title":"Keep me","status":"active"}}"""
        assertThrows(IllegalStateException::class.java) {
            TaskImport.preview(ByteArrayInputStream(("{\"format\":\"daymark-export\",\"version\":1}\n"+task+"\n"+task+"\n").toByteArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val bad="{\"format\":\"daymark-export\",\"version\":1}\n"+task.replace("Keep me","x".repeat(161))+"\n"
            TaskImport.preview(ByteArrayInputStream(bad.toByteArray()))
        }
    }
}
