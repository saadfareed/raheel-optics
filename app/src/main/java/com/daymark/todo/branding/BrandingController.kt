package com.daymark.todo.branding

import android.content.Context
import android.graphics.Bitmap
import com.daymark.todo.R
import com.daymark.todo.core.editChecked
import com.daymark.todo.identity.AuthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.net.URI
import com.daymark.todo.identity.awaitResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class BrandConfig(val name:String, val tagline:String, val logoUrl:String="") {
    fun validate(projectUrl:String) {
        require(name.isNotBlank() && name.length<=40 && name.none(Char::isISOControl))
        require(tagline.length<=100 && tagline.none(Char::isISOControl))
        if(logoUrl.isNotBlank()) {
            val logo=URI(logoUrl);val project=URI(projectUrl)
            require(logo.scheme=="https" && logo.host==project.host && logo.port==project.port && logo.userInfo==null && logo.query==null && logo.fragment==null)
            require(logo.path.startsWith("/storage/v1/object/public/branding/") && logo.path.split('/').none {it==".."})
        }
    }
}
data class BrandAppearance(val config:BrandConfig,val logo:Bitmap?=null)

/** Public, bounded appearance data. It cannot change identity, auth endpoints or permissions. */
class BrandingController(private val context:Context, private val auth:AuthClient, private val projectUrl:String) {
    private val json=Json {ignoreUnknownKeys=true}
    private val prefs=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
    private val logoFile=File(context.filesDir,"brand-logo.bin")
    private val lock=Mutex()
    private val defaults=BrandConfig(context.getString(R.string.app_name),context.getString(R.string.brand_tagline))
    private val mutable=MutableStateFlow(BrandAppearance(defaults))
    private var cachedLoaded=false
    val state=mutable.asStateFlow()

    suspend fun refresh(force:Boolean=false)=lock.withLock {withContext(Dispatchers.IO) {
        try {
            if(!cachedLoaded) {
                val cached=runCatching {prefs.getString("config",null)?.let {json.decodeFromString<BrandConfig>(it).also {config ->config.validate(projectUrl)}}}.getOrNull()
                if(cached!=null) mutable.value=BrandAppearance(cached,if(cached.logoUrl.isBlank()) null else LogoImages.decode(logoFile.takeIf {it.exists() && it.length()<=LogoImages.MaxBytes}?.readBytes()))
                cachedLoaded=true
            }
            val now=System.currentTimeMillis()
            val last=prefs.getLong("checked",0)
            if(!force && now>=last && now-last<86400000L) return@withContext
            val response=auth.request("/rest/v1/app_appearance?id=eq.default&select=name,tagline,logoUrl",method="GET").jsonArray
            val config=response.firstOrNull()?.let {json.decodeFromJsonElement<BrandConfig>(it)} ?: defaults
            config.validate(projectUrl)
            val bytes=if(config.logoUrl.isBlank()) null else downloadLogo(config.logoUrl)
            val logo=if(bytes==null) null else LogoImages.decode(bytes) ?: error("Invalid logo")
            if(logo!=null) {
                val temporary=File(context.filesDir,"brand-logo.tmp")
                temporary.outputStream().use {it.write(LogoImages.encode(logo))}
                check(temporary.renameTo(logoFile))
            } else logoFile.takeIf {it.exists()}?.delete()
            prefs.editChecked {putString("config",json.encodeToString(config));putLong("checked",now)}
            mutable.value=BrandAppearance(config,logo)
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) { /* Keep the bundled/cached brand if the service is unavailable. */ }
    }}
    private suspend fun downloadLogo(url:String):ByteArray {
        return logoClient.newCall(Request.Builder().url(url).build()).awaitResponse {response ->
            check(response.code==200)
            check(response.body.contentLength()<=LogoImages.MaxBytes)
            response.body.byteStream().use {input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {val count=input.read(buffer);if(count<0) break;check(output.size()+count<=LogoImages.MaxBytes);output.write(buffer,0,count)}
                output.toByteArray()
            }
        }
    }
    private companion object {
        val logoClient=OkHttpClient.Builder().connectTimeout(2500,TimeUnit.MILLISECONDS).readTimeout(2500,TimeUnit.MILLISECONDS)
            .callTimeout(5,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
