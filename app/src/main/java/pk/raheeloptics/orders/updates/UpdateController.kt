package pk.raheeloptics.orders.updates

import android.content.Context
import androidx.core.content.edit
import pk.raheeloptics.orders.core.editChecked
import pk.raheeloptics.orders.BuildConfig
import pk.raheeloptics.orders.identity.BackendTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

interface UpdateStorage {
    fun policy(): String?
    fun savePolicy(value: String?)
    fun dismissedVersion(): Int
    fun dismiss(version: Int)
}

private class AndroidUpdateStorage(context: Context) : UpdateStorage {
    private val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    override fun policy() = prefs.getString("policy", null)
    override fun savePolicy(value: String?) {
        prefs.editChecked("Unable to save update policy") { putString("policy", value) }
    }
    override fun dismissedVersion() = prefs.getInt("dismissed", 0)
    override fun dismiss(version: Int) { prefs.edit { putInt("dismissed", version) } }
}

class UpdateController(
    private val storage: UpdateStorage,
    private val backend: BackendTransport,
    private val installedVersion: Int
) {
    constructor(context: Context, backend: BackendTransport) : this(AndroidUpdateStorage(context), backend, BuildConfig.VERSION_CODE)
    private val codec = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    val policy = MutableStateFlow(readCache())
    val checking = MutableStateFlow(false)
    val checked = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val dismissedVersion = MutableStateFlow(storage.dismissedVersion())

    private fun readCache(): UpdatePolicy? = runCatching {
        storage.policy()?.let { codec.decodeFromString<UpdatePolicy>(it).validate() }
    }.getOrNull()

    suspend fun check() = lock.withLock {
        checking.value = true
        error.value = null
        try {
            val rows = backend.request("/rest/v1/app_update_policy?platform=eq.android&select=latest_version_code,minimum_version_code,version_name,update_url,message", "GET").jsonArray
            val latest = rows.singleOrNull()?.let { codec.decodeFromString<UpdatePolicy>(it.toString()).validate() }
            check(rows.size <= 1)
            // Empty successful responses revoke the policy. Failures retain cached hard updates.
            policy.value = latest
            checked.value = true
            // Enforce a received hard update even if persisting it fails.
            try {
                withContext(Dispatchers.IO) {
                    storage.savePolicy(latest?.let { codec.encodeToString(UpdatePolicy.serializer(), it) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = "Update policy received, but could not be saved for offline use. Please try again."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error.value = "Could not check for updates. Check your connection and try again."
            checked.value = true
        } finally {
            checking.value = false
        }
    }

    fun dismiss() {
        val current = policy.value ?: return
        if (current.kind(installedVersion) != UpdateKind.SOFT) return
        dismissedVersion.value = current.latest_version_code
        storage.dismiss(current.latest_version_code)
    }
}
