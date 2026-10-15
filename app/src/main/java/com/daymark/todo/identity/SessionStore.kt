package com.daymark.todo.identity

import android.content.Context
import com.daymark.todo.core.editChecked
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Session(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAt: Long, val localOnly: Boolean = false)
@Serializable
data class CloudConfig(val supabaseUrl: String = "", val publicKey: String = "", val powerSyncUrl: String = "") {
    val configured: Boolean get() = secureUrl(supabaseUrl) && publicKey.isNotBlank()
    private fun secureUrl(value: String): Boolean = runCatching { java.net.URI(value).let { it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null && it.query==null && it.fragment==null } }.getOrDefault(false)
    fun validate() {
        require(configured) { "Enter the project HTTPS URL and public client key." }
        require(!publicKey.startsWith("sb_secret_") && !publicKey.contains("service_role")) { "Use a public client key, never a secret or service-role key." }
        if(publicKey.count {it=='.'}==2) {
            val claims=runCatching { String(java.util.Base64.getUrlDecoder().decode(publicKey.split('.')[1])) }.getOrDefault("")
            require(!claims.contains("service_role")) { "Use a public anon key, never a service-role key." }
        }
    }
}

interface AuthSessionStorage {
    fun session(): Session?
    /** Retained credentials are only accessible for revocation, never normal app access. */
    fun sessionForRevocation(): Session?
    fun save(session: Session)
    fun clear()
    fun markOpened()
    fun config(): CloudConfig
}

class SessionStore(context: Context) : AuthSessionStorage {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var cachedSession: Pair<String,Session>? = null
    private val key: SecretKey get() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("daymark-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("daymark-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    override fun session(): Session? {
        val encoded = prefs.getString("session", null) ?: return null
        if(!prefs.contains("lastOpened")) prefs.editChecked {putLong("lastOpened",System.currentTimeMillis())}
        if(com.daymark.todo.core.SessionPolicy.expired(prefs.getLong("lastOpened",0),System.currentTimeMillis(),idleDays())) return null
        return decodeSession(encoded)
    }
    override fun sessionForRevocation(): Session? = prefs.getString("session", null)?.let(::decodeSession)
    private fun decodeSession(encoded: String): Session? {
        cachedSession?.takeIf {it.first==encoded}?.let {return it.second}
        return runCatching {
            val parts = encoded.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            json.decodeFromString<Session>(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))).also {
                cachedSession=encoded to it
            }
        }.getOrNull()
    }
    override fun save(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(json.encodeToString(session).toByteArray()), Base64.NO_WRAP)
        prefs.editChecked("Could not save your session.") { putString("session", value);if(!prefs.contains("lastOpened")) putLong("lastOpened",System.currentTimeMillis()) }
        cachedSession=value to session
    }
    override fun clear() { prefs.editChecked { remove("session");remove("lastOpened") }; cachedSession=null }
    fun prepareGuestTransfer() {prefs.editChecked {putBoolean("guestTransfer",true)}}
    fun guestTransferPending():Boolean=prefs.getBoolean("guestTransfer",false)
    fun guestTransferBound():Boolean=guestTransferPending() && prefs.getString("guestDestination",null)!=null
    fun bindGuestTransfer(session:Session) {
        if(!session.localOnly && guestTransferPending() && prefs.getString("guestDestination",null)==null)
            prefs.editChecked {putString("guestDestination",workspaceKey(session))}
    }
    fun guestTransferOwnedBy(session:Session):Boolean=guestTransferPending() && prefs.getString("guestDestination",null)==workspaceKey(session)
    fun guestTransferCursor():String=prefs.getString("guestCursor","").orEmpty()
    fun advanceGuestTransfer(id:String) {prefs.editChecked {putString("guestCursor",id)}}
    fun finishGuestTransfer() {prefs.editChecked {remove("guestTransfer");remove("guestDestination");remove("guestCursor")}}
    fun idleDays():Int=prefs.getInt("idleDays",30).takeIf {it in com.daymark.todo.core.SessionPolicy.options} ?: 30
    fun setIdleDays(days:Int) {
        require(days in com.daymark.todo.core.SessionPolicy.options)
        prefs.editChecked {putInt("idleDays",days)}
    }
    override fun markOpened() {if(session()!=null) prefs.editChecked {putLong("lastOpened",System.currentTimeMillis())}}
    fun hasExpiredSession():Boolean=prefs.contains("session") && session()==null
    override fun config(): CloudConfig = prefs.getString("config", null)?.let { runCatching { json.decodeFromString<CloudConfig>(it) }.getOrNull() } ?: CloudConfig()
    fun workspaceKey(session: Session): String {
        if(session.localOnly) return "local-device"
        val project=com.daymark.todo.core.WorkspaceIdentity.project(config().supabaseUrl,"")
        val binding="projectPrefix:$project"
        val prefix=prefs.getString(binding,null) ?: run {
            val legacy=prefs.getString("legacyProject",null)
            val value=if(legacy==null || legacy==project) "" else project
            prefs.editChecked {
                putString(binding,value)
                if(value.isEmpty()) putString("legacyProject",project)
            }
            value
        }
        return if(prefix.isEmpty()) session.userId else "$prefix-${session.userId}"
    }
    private fun workspaceRegistry(session:Session):String = "workspaces:${com.daymark.todo.core.WorkspaceIdentity.project(config().supabaseUrl,"")}:${session.userId}"
    fun rememberWorkspace(session:Session,filename:String) {
        require(Regex("todos-[a-zA-Z0-9-]+\\.db").matches(filename))
        val registry=workspaceRegistry(session)
        val names=prefs.getStringSet(registry,emptySet()).orEmpty()+filename
        prefs.editChecked {putStringSet(registry,names)}
    }
    fun retainedWorkspaces(session:Session):Set<String> {
        val current="todos-${workspaceKey(session).replace(Regex("[^a-zA-Z0-9-]"),"")}.db"
        return (prefs.getStringSet(workspaceRegistry(session),emptySet()).orEmpty()+current)
            .filter {Regex("todos-[a-zA-Z0-9-]+\\.db").matches(it)}.toSet()
    }
    fun forgetWorkspaces(session:Session) {prefs.editChecked {remove(workspaceRegistry(session))}}
    fun pendingErasures():Set<String> = prefs.getStringSet("pendingErasures",emptySet()).orEmpty()
        .filter {Regex("todos-[a-zA-Z0-9-]+\\.db").matches(it)}.toSet()
    fun markForErasure(names:Set<String>) {
        require(names.all {Regex("todos-[a-zA-Z0-9-]+\\.db").matches(it)})
        prefs.editChecked {putStringSet("pendingErasures",pendingErasures()+names)}
    }
    fun finishErasure(name:String) {prefs.editChecked {putStringSet("pendingErasures",pendingErasures()-name)}}
    fun configure(config: CloudConfig) {
        config.validate()
        val previous=config()
        prefs.editChecked {
            if(previous.configured) {
                val canonical=com.daymark.todo.core.WorkspaceIdentity.project(previous.supabaseUrl,"")
                val old=com.daymark.todo.core.WorkspaceIdentity.project(previous.supabaseUrl,previous.powerSyncUrl)
                val legacy=prefs.getString("legacyProject",null)
                val prefix=prefs.getString("projectPrefix:$canonical",null)
                    ?: if(legacy==null || legacy==old || legacy==canonical) "" else old
                putString("projectPrefix:$canonical",prefix)
                if(prefix.isEmpty()) putString("legacyProject",canonical)
            }
            putString("config",json.encodeToString(config))
        }
    }
}
