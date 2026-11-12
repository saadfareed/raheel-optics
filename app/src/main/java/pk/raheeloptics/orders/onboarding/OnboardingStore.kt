package pk.raheeloptics.orders.onboarding

import pk.raheeloptics.orders.data.WorkspaceFiles
import android.content.Context
import pk.raheeloptics.orders.core.OnboardingPolicy
import pk.raheeloptics.orders.core.editChecked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Installation-scoped: sign-out and app upgrades never reset the introduction. */
class OnboardingStore(context:Context,existingAccount:Boolean) {
    private val prefs=context.getSharedPreferences("onboarding",Context.MODE_PRIVATE)
    private val existingWorkspace=existingAccount || context.databaseList().any {WorkspaceFiles.name.matches(it) || WorkspaceFiles.legacy.matches(it)}
    private val mutable=MutableStateFlow(OnboardingPolicy.shouldShow(prefs.getBoolean("completed",false),existingWorkspace))
    val required=mutable.asStateFlow()
    suspend fun complete()=withContext(Dispatchers.IO) {
        prefs.editChecked {putBoolean("completed",true)}
        mutable.value=false
    }
}
