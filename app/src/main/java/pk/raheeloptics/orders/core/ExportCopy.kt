package pk.raheeloptics.orders.core

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caller owns both streams and the dispatcher; provider writes may still block in the OS. */
suspend fun copyExport(input:InputStream,output:OutputStream):Long {
    val context=currentCoroutineContext()
    val buffer=ByteArray(8192)
    var copied=0L
    while(true) {
        context.ensureActive()
        val count=input.read(buffer)
        if(count<0) return copied
        context.ensureActive()
        output.write(buffer,0,count)
        copied+=count
    }
}
