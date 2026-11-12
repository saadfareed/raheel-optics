package com.daymark.todo

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Firebase Crashlytics. Collection is off in debug and QA builds and on for everything else, and only when the app was
 * built with a google-services.json (otherwise Firebase is not initialised and this does nothing).
 */
object CrashReporting {
    fun start(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        runCatching { FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG }
    }
}
