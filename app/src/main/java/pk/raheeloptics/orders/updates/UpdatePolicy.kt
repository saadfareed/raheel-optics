package pk.raheeloptics.orders.updates

import kotlinx.serialization.Serializable
import java.net.URI

enum class UpdateKind { NONE, SOFT, HARD }

@Serializable
data class UpdatePolicy(
    val latest_version_code: Int,
    val minimum_version_code: Int,
    val version_name: String,
    val update_url: String,
    val message: String = "A new version of Daymark is available."
) {
    fun validate(): UpdatePolicy {
        require(minimum_version_code > 0 && latest_version_code >= minimum_version_code)
        require(version_name.isNotBlank())
        val url = URI(update_url)
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null)
        return this
    }
    fun kind(installed: Int): UpdateKind = when {
        installed < minimum_version_code -> UpdateKind.HARD
        installed < latest_version_code -> UpdateKind.SOFT
        else -> UpdateKind.NONE
    }
    fun destination(installer: String?, applicationId: String): String =
        if(installer=="com.android.vending") "https://play.google.com/store/apps/details?id=$applicationId" else update_url
}
