package com.daymark.todo.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/** Persist the app's appearance for Android's splash, including the next cold launch. */
fun syncLaunchTheme(context: Context) {
    if (Build.VERSION.SDK_INT < 31) return
    val choice = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "System")
    val mode = when (choice) {
        "Light" -> UiModeManager.MODE_NIGHT_NO
        "Dark" -> UiModeManager.MODE_NIGHT_YES
        // Application AUTO clears the package override and inherits the system configuration.
        else -> UiModeManager.MODE_NIGHT_AUTO
    }
    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
}
