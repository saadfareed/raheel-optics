package pk.raheeloptics.orders.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Every local cleanup step runs even if an earlier step fails or the caller is cancelled. */
suspend fun finishSignOut(vararg steps: suspend () -> Unit): Unit = withContext(NonCancellable) {
    var failure: Throwable? = null
    for (step in steps) {
        try { step() }
        catch (e: Exception) {
            val first = failure
            if (first == null) failure = e else if (first !== e) first.addSuppressed(e)
        }
    }
    failure?.let { throw it }
}
