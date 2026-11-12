package pk.raheeloptics.orders.identity

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException

/** Cancellation closes header waits and body reads. The callback owns the response. */
internal suspend fun <T> Call.awaitResponse(read:(Response)->T):T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {cancel()}
    enqueue(object:Callback {
        override fun onFailure(call:Call,e:IOException) {continuation.resumeWith(Result.failure(e))}
        override fun onResponse(call:Call,response:Response) {
            continuation.resumeWith(runCatching {response.use(read)})
        }
    })
}
