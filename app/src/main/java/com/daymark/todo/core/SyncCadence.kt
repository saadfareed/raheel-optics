package com.daymark.todo.core

import kotlin.math.min

/** Spread polling, worker starts and offline retries; local saves remain immediate. */
object SyncCadence {
    fun startupDelayMillis(sample:Double):Long {
        require(sample.isFinite() && sample in 0.0..1.0)
        return (sample*3000).toLong()
    }
    fun delayMillis(seconds:Int,failures:Int=0,sample:Double):Long {
        require(seconds>0 && failures>=0 && sample.isFinite() && sample in 0.0..1.0)
        val configured=seconds.toLong()*1000
        val base=min(configured*(1L shl failures.coerceAtMost(4)),maxOf(configured,900000L))
        return base+(base*.2*sample).toLong()
    }
}
