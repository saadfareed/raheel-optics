package pk.raheeloptics.orders

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import pk.raheeloptics.orders.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val target=mutableStateOf<String?>(null)
    override fun attachBaseContext(base: android.content.Context) {
        // Before Android 12, resolve launch resources before the activity creates its window.
        val choice=base.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE).getString("theme","System")
        val themed=if(android.os.Build.VERSION.SDK_INT<31 && choice in setOf("Light","Dark")) {
            val config=android.content.res.Configuration(base.resources.configuration)
            config.uiMode=(config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if(choice=="Dark") android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            base.createConfigurationContext(config)
        } else base
        super.attachBaseContext(themed)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        target.value=intent.getStringExtra("todo")
        (application as RaheelApp).scope.launch { (application as RaheelApp).branding.refresh() }
        setContent {
            pk.raheeloptics.orders.ui.AppTheme {
                pk.raheeloptics.orders.updates.UpdateGate((application as RaheelApp).updates) {
                    AppRoot(target.value,consumeTarget={ target.value=null })
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); target.value=intent.getStringExtra("todo") }
}
