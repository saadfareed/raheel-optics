package com.daymark.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.daymark.todo.R

/** Only the vectors used by this app; avoid packaging an entire icon catalogue. */
internal object DaymarkIcons {
    val Download:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_download)
    val Upload:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_upload)
    val Info:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_info)
    val Logout:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_logout)
    val Notes:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_notes)
    val Add:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_add)
    val Alarm:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_alarm)
    val Check:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_check)
    val CheckCircle:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_check_circle)
    val ChevronRight:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_chevron_right)
    val Close:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_close)
    val CloudDone:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_cloud_done)
    val CloudOff:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_cloud_off)
    val DeleteOutline:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_delete_outline)
    val DonutLarge:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_donut_large)
    val Edit:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_edit)
    val Event:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_event)
    val ExpandLess:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_expand_less)
    val ExpandMore:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_expand_more)
    val FilterAltOff:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_filter_alt_off)
    val GridView:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_grid_view)
    val History:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_history)
    val Lock:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_lock)
    val Notifications:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_notifications)
    val NotificationsActive:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_notifications_active)
    val NotificationsNone:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_notifications_none)
    val NotificationsOff:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_notifications_off)
    val Palette:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_palette)
    val Person:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_person)
    val PrivacyTip:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_privacy_tip)
    val RadioButtonUnchecked:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_radio_button_unchecked)
    val Replay:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_replay)
    val Search:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_search)
    val Schedule:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_schedule)
    val Settings:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_settings)
    val Storage:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_storage)
    val Timer:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_timer)
    val Tune:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_tune)
    val VerifiedUser:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_verified_user)
    val Visibility:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_visibility)
    val VisibilityOff:ImageVector @Composable get()=ImageVector.vectorResource(R.drawable.ic_ui_visibility_off)
}
