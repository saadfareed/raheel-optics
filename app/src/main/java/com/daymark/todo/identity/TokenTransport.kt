package com.daymark.todo.identity

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

fun interface TokenTransport {
    suspend fun request(config: CloudConfig, path: String, method: String, body: JsonElement?, token: String?): JsonElement
}

class HttpTokenTransport(private val client: OkHttpClient = defaultClient) : TokenTransport {
    override suspend fun request(config: CloudConfig, path: String, method: String, body: JsonElement?, token: String?): JsonElement {
        check(config.configured) { "Service unavailable. Please try again later." }
        val quick = path.startsWith("/rest/v1/app_update_policy?") || path.startsWith("/rest/v1/app_appearance?") || path.startsWith("/auth/v1/logout")
        val selected = when {
            quick -> client.newBuilder().connectTimeout(2500,TimeUnit.MILLISECONDS).readTimeout(2500,TimeUnit.MILLISECONDS).callTimeout(5,TimeUnit.SECONDS).build()
            path == "/functions/v1/delete-account" -> client.newBuilder().readTimeout(60,TimeUnit.SECONDS).callTimeout(80,TimeUnit.SECONDS).build()
            else -> client
        }
        val mediaType="application/json; charset=utf-8".toMediaType()
        val requestBody = body?.toString()?.toRequestBody(mediaType)
            ?: if(method in setOf("POST","PUT","PATCH")) "".toRequestBody(mediaType) else null
        val builder=Request.Builder().url(config.supabaseUrl.trimEnd('/')+path).method(method,requestBody)
            .header("Cache-Control","no-cache")
        backendHeaders(config.publicKey,token).forEach { (name,value) -> builder.header(name,value) }
        val call=selected.newCall(builder.build())
        return call.awaitResponse {
                        val maxBytes=ResponseLimits.forPath(path)
                        check(it.body.contentLength()<=maxBytes) {"The server response is too large. Try again."}
                        val text=ResponseLimits.read(it.body.byteStream(),maxBytes)
                        val json=runCatching { Json.parseToJsonElement(text) }.getOrDefault(JsonNull)
                        if(!it.isSuccessful) {
                            val message=(json as? JsonObject)?.let { obj -> listOf("msg","message","error_description").firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.contentOrNull } }
                            throw ApiException.fromResponse(it.code,message ?: "Request failed (${it.code}). Try again when connected.",json)
                        }
                        json
        }
    }
    companion object {
        private val defaultClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
            .writeTimeout(20,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
