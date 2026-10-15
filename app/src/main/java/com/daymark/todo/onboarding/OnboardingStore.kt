package com.daymark.todo.onboarding

import android.content.Context
import com.daymark.todo.core.OnboardingPolicy
import com.daymark.todo.core.editChecked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Installation-scoped: sign-out and app upgrades never reset the introduction. */
class OnboardingStore(context:Context,existingAccount:Boolean) {
    private val prefs=context.getSharedPreferences("onboarding",Context.MODE_PRIVATE)
    private val existingWorkspace=existingAccount || context.databaseList().any {it.startsWith("todos-")}
    private val mutable=MutableStateFlow(OnboardingPolicy.shouldShow(prefs.getBoolean("completed",false),existingWorkspace))
    val required=mutable.asStateFlow()
    suspend fun complete()=withContext(Dispatchers.IO) {
        prefs.editChecked {putBoolean("completed",true)}
        mutable.value=false
    }
}
