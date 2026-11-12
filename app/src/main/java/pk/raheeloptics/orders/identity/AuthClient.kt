package pk.raheeloptics.orders.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class AuthClient(private val sessions: AuthSessionStorage, private val transport: TokenTransport = HttpTokenTransport()) : BackendTransport {
    override suspend fun accountId(): String = validSession().userId
    private val refreshLock = Mutex()
    override suspend fun request(path: String, method: String, body: JsonElement?, authenticated: Boolean): JsonElement {
        val token = if (authenticated) validSession().accessToken else null
        return try { requestWithToken(path,method,body,token) }
        catch(e:ApiException) {
            if(!authenticated || e.status!=401 || token==null) throw e
            // Retry once with a refreshed token; concurrent refreshes reuse the new token.
            requestWithToken(path,method,body,validSession(forceToken=token).accessToken)
        }
    }
    private suspend fun requestWithToken(path: String, method: String, body: JsonElement?, token: String?): JsonElement =
        transport.request(sessions.config(), path, method, body, token)
    suspend fun login(email: String, password: String) {
        require(email.contains("@") && password.isNotBlank()) { "Enter your email and password." }
        val response = request("/auth/v1/token?grant_type=password", body = buildJsonObject { put("email", email.trim()); put("password", password) })
        sessions.clear()
        saveResponse(response.jsonObject)
        sessions.markOpened()
    }
    suspend fun register(email: String, password: String): Boolean {
        require(email.contains("@") && password.length >= 12) { "Enter an email and a password of at least 12 characters." }
        val response = request("/auth/v1/signup", body = buildJsonObject { put("email", email.trim()); put("password", password) }).jsonObject
        return if (response["access_token"]?.jsonPrimitive?.contentOrNull != null) { sessions.clear(); saveResponse(response); sessions.markOpened(); true } else false
    }
    /** Confirms a 6-digit email code ([type] "signup" or "recovery") and signs in; recovery then sets [newPassword]. */
    suspend fun verifyCode(email: String, code: String, type: String, newPassword: String? = null) {
        require(code.length in 6..10 && code.all(Char::isDigit)) { "Enter the code from the email." }
        if (newPassword != null) require(newPassword.length >= 12) { "Use a password of at least 12 characters." }
        val response = request("/auth/v1/verify", body = buildJsonObject { put("type", type); put("email", email.trim()); put("token", code) }).jsonObject
        sessions.clear(); saveResponse(response)
        if (newPassword != null) requestWithToken("/auth/v1/user", "PUT", buildJsonObject { put("password", newPassword) }, response["access_token"]!!.jsonPrimitive.content)
        sessions.markOpened()
    }
    suspend fun resendSignupCode(email: String) {
        request("/auth/v1/resend", body = buildJsonObject { put("type", "signup"); put("email", email.trim()) })
    }
    suspend fun reset(email: String) {
        require(email.contains("@")) { "Enter your email address." }
        request("/auth/v1/recover", body = buildJsonObject { put("email", email.trim()) })
    }
    suspend fun changePassword(current: String, replacement: String) = refreshLock.withLock {
        pk.raheeloptics.orders.core.PasswordRules.validate(current,replacement)
        val session=sessions.session() ?: error("Sign in first.")
        check(!session.localOnly) { "Sign in first." }
        // Verify the current password with Supabase without switching the app's account.
        val verified=requestWithToken("/auth/v1/token?grant_type=password","POST",buildJsonObject {
            put("email",session.email); put("password",current)
        },null).jsonObject
        check(verified["user"]!!.jsonObject["id"]!!.jsonPrimitive.content == session.userId) { "Account changed. Sign in again." }
        saveResponse(verified)
        requestWithToken("/auth/v1/user","PUT",buildJsonObject {
            put("password",replacement); put("current_password",current)
        },verified["access_token"]!!.jsonPrimitive.content)
    }

    suspend fun deleteAccount(currentPassword: String) {
        require(currentPassword.isNotBlank()) { "Enter your current password." }
        request("/functions/v1/delete-account", body = buildJsonObject {
            put("current_password", currentPassword)
        }, authenticated = true)
    }

    suspend fun revokeCurrentSession() {
        refreshLock.withLock {
            val session = sessions.sessionForRevocation() ?: return@withLock
            if (session.localOnly) return@withLock
            var token = session.accessToken
            var refreshed = false
            if (session.expiresAt <= System.currentTimeMillis() + 60000) {
                token = refreshForRevocation(session)
                refreshed = true
            }
            try {
                requestWithToken("/auth/v1/logout?scope=local", "POST", null, token)
            } catch (e: ApiException) {
                if (e.status != 401 || refreshed) throw e
                // A still-valid token can have been rejected/revoked server-side.
                token = refreshForRevocation(sessions.sessionForRevocation() ?: session)
                requestWithToken("/auth/v1/logout?scope=local", "POST", null, token)
            }
        }
    }

    private suspend fun refreshForRevocation(session: Session): String {
        val result = requestWithToken("/auth/v1/token?grant_type=refresh_token", "POST",
            buildJsonObject { put("refresh_token", session.refreshToken) }, null).jsonObject
        check(result["user"]!!.jsonObject["id"]!!.jsonPrimitive.content == session.userId) { "Account changed during sign-out." }
        // Persist rotated credentials without extending inactivity. Revocation can run
        // after local inactivity expiry, so do not use session() to read the result.
        try { saveResponse(result) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            // Sign-out can revoke using the received token even when local storage
            // cannot persist rotation. The caller still clears local credentials.
        }
        return result["access_token"]!!.jsonPrimitive.content
    }

    suspend fun validSession(forceToken:String?=null): Session = refreshLock.withLock {
        val session = sessions.session() ?: error("Sign in to synchronize your todos.")
        check(!session.localOnly) { "This workspace is local-only." }
        if (session.expiresAt > System.currentTimeMillis() + 60000 && session.accessToken!=forceToken) return@withLock session
        val result = try {
            requestWithToken("/auth/v1/token?grant_type=refresh_token", "POST", buildJsonObject { put("refresh_token", session.refreshToken) }, null).jsonObject
        } catch(e:ApiException) {
            if(e.status in listOf(400,401)) sessions.clear()
            throw e
        }
        saveResponse(result)
        sessions.session() ?: error("Sign in again; your local changes are safe.")
    }
    private fun saveResponse(obj: JsonObject) {
        val user = obj["user"]!!.jsonObject
        sessions.save(Session(user["id"]!!.jsonPrimitive.content, user["email"]?.jsonPrimitive?.content ?: "", obj["access_token"]!!.jsonPrimitive.content,
            obj["refresh_token"]!!.jsonPrimitive.content, System.currentTimeMillis() + (obj["expires_in"]?.jsonPrimitive?.long ?: 3600) * 1000))
    }
}
