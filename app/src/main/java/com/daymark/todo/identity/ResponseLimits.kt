package com.daymark.todo.identity

import java.io.ByteArrayOutputStream
import java.io.InputStream

object ResponseLimits {
    const val MaxJsonBytes=4*1024*1024
    fun forPath(path:String):Int = when {
        path.startsWith("/rest/v1/app_update_policy?") || path.startsWith("/rest/v1/app_appearance?") -> 64*1024
        path.startsWith("/auth/") || path.startsWith("/functions/") -> 128*1024
        path.endsWith("/apply_operation") || path.endsWith("/apply_operations") || path.endsWith("/analytics_summary") -> 128*1024
        else -> MaxJsonBytes
    }
    fun read(input:InputStream,maxBytes:Int=MaxJsonBytes):String {
        require(maxBytes>0)
        val output=ByteArrayOutputStream(minOf(8192,maxBytes))
        val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer)
            if(count<0) break
            check(output.size()+count<=maxBytes) {"The server response is too large. Try again."}
            output.write(buffer,0,count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
