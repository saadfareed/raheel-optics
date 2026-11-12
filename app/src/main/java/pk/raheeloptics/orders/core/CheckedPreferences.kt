package pk.raheeloptics.orders.core

import android.content.SharedPreferences
import android.annotation.SuppressLint

/** KTX edit returns Unit; this variant retains failure detection for durable data. */
// KTX edit discards commit() failure; checked durability requires the platform editor.
@SuppressLint("UseKtx")
internal inline fun SharedPreferences.editChecked(
    message: String = "Could not save local preferences.",
    action: SharedPreferences.Editor.() -> Unit
) {
    val editor = edit()
    editor.action()
    check(editor.commit()) { message }
}
