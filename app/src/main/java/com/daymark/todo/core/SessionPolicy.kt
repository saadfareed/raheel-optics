package com.daymark.todo.core

object SessionPolicy {
    val options=listOf(7,30,90,0)
    fun expired(lastOpened:Long,now:Long,idleDays:Int):Boolean {
        require(idleDays in options)
        return idleDays>0 && now-lastOpened>=idleDays*86400000L
    }
}
