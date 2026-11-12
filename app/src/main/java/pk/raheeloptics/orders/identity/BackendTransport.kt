package pk.raheeloptics.orders.identity

import kotlinx.serialization.json.*

enum class AdmissionFailure { RATE_LIMIT, TASK_QUOTA, HISTORY_QUOTA, RECEIPT_QUOTA }
class ApiException(val status: Int, message: String, val admissionFailure:AdmissionFailure?=null, val retryAfterSeconds:Int?=null) : Exception(message) {
    val permanentQuota:Boolean get()=admissionFailure in setOf(AdmissionFailure.TASK_QUOTA,AdmissionFailure.HISTORY_QUOTA,AdmissionFailure.RECEIPT_QUOTA)
    companion object {
        fun fromResponse(status:Int,message:String,body:JsonElement?):ApiException {
            val details=(body as? JsonObject)?.get("details")
            val metadata=when(details) {
                is JsonObject -> details
                is JsonPrimitive -> runCatching {Json.parseToJsonElement(details.content) as? JsonObject}.getOrNull()
                else -> null
            }
            val category=(metadata?.get("category") as? JsonPrimitive)?.contentOrNull
            val failure=if(status==429) when(category) {
                "rate_limit" -> AdmissionFailure.RATE_LIMIT
                "task_quota" -> AdmissionFailure.TASK_QUOTA
                "history_quota" -> AdmissionFailure.HISTORY_QUOTA
                "receipt_quota" -> AdmissionFailure.RECEIPT_QUOTA
                else -> null
            } else null
            val retry=(metadata?.get("retryAfterSeconds") as? JsonPrimitive)?.intOrNull?.takeIf {it in 1..3600}
            return ApiException(status,message,failure,retry)
        }
    }
}

interface BackendTransport {
    suspend fun accountId(): String
    suspend fun request(path: String, method: String = "POST", body: JsonElement? = null, authenticated: Boolean = false): JsonElement
}
