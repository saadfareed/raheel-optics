package pk.raheeloptics.orders.identity

/** Publishable API keys identify the app; only JWTs belong in Authorization. */
internal fun backendHeaders(publicKey: String, accessToken: String? = null): Map<String, String> {
    val token = accessToken ?: publicKey.takeIf { it.count { character -> character == '.' } == 2 }
    return buildMap {
        put("apikey", publicKey)
        token?.let { put("Authorization", "Bearer $it") }
        put("Content-Type", "application/json")
    }
}
