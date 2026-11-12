package pk.raheeloptics.orders

import pk.raheeloptics.orders.identity.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AdmissionTransportTest {
    @Test fun httpQuotaDetailsBecomePermanentAdmissionFailure()=runBlocking {
        val response=buildJsonObject {
            put("message","History quota")
            put("details",buildJsonObject {put("category","history_quota");put("limit",1);put("usage",1)}.toString())
        }
        val client=OkHttpClient.Builder().addInterceptor {chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                .body(response.toString().toResponseBody()).build()
        }.build()
        try {
            try {
                HttpTokenTransport(client).request(CloudConfig("https://example.invalid","public-key"),"/rest/v1/rpc/apply_operation","POST",JsonNull,"token")
                fail("Quota error ignored")
            } catch(error:ApiException) {
                assertEquals(429,error.status)
                assertEquals(AdmissionFailure.HISTORY_QUOTA,error.admissionFailure)
                assertTrue(error.permanentQuota)
            }
        } finally {client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
    }
}
