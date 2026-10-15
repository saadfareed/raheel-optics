package com.daymark.todo

import com.daymark.todo.core.copyExport
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportCopyTest {
    @Test fun copiesCompleteSnapshotIncludingFinalPartialChunk()=runBlocking {
        val bytes=ByteArray(8192*4+67) {it.toByte()}
        val output=ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(),copyExport(bytes.inputStream(),output))
        assertArrayEquals(bytes,output.toByteArray())
    }
    @Test fun cancellationDuringDestinationWriteStopsBeforeReadingAnotherChunk()=runBlocking {
        val bytes=ByteArray(8192*100) {it.toByte()}
        val input=bytes.inputStream()
        val saved=ByteArrayOutputStream()
        val job=launch {
            val copying=currentCoroutineContext().job
            val output=object : java.io.OutputStream() {
                override fun write(value:Int) {error("Unexpected single-byte write")}
                override fun write(buffer:ByteArray,offset:Int,length:Int) {
                    saved.write(buffer,offset,length)
                    copying.cancel()
                }
            }
            copyExport(input,output)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(8192,saved.size())
        assertEquals(bytes.size-8192,input.available())
        assertArrayEquals(bytes.copyOfRange(0,8192),saved.toByteArray())
    }
}
