package pk.raheeloptics.orders.ui

import androidx.core.content.edit
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import pk.raheeloptics.orders.core.*
import java.time.*

internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface

internal val Violet:Color @Composable get()=MaterialTheme.colorScheme.primary

internal val Paper:Color @Composable get()=MaterialTheme.colorScheme.background

internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant

internal val Mint:Color @Composable get()=MaterialTheme.colorScheme.secondary

internal val Coral:Color @Composable get()=MaterialTheme.colorScheme.error

internal val Amber:Color @Composable get()=if(MaterialTheme.colorScheme.background.red<.2f) Color(0xFFFFCE80) else Color(0xFF8A5F0E)

internal val lightScheme=lightColorScheme(primary=Color(0xFF075A91),onPrimary=Color.White,secondary=Color(0xFF087F67),background=Color(0xFFF5F9FC),
    surface=Color.White,surfaceVariant=Color(0xFFE5F0F7),onSurface=Color(0xFF102A3A),onBackground=Color(0xFF102A3A),onSurfaceVariant=Color(0xFF587080),error=Color(0xFFB64032))

internal val darkScheme=darkColorScheme(primary=Color(0xFF78C7F5),secondary=Color(0xFF69D8B8),background=Color(0xFF071923),
    surface=Color(0xFF102833),surfaceVariant=Color(0xFF193A49),onSurface=Color(0xFFE7F3F9),onBackground=Color(0xFFE7F3F9),onSurfaceVariant=Color(0xFFAEC5D1),error=Color(0xFFFFB4A8))

internal val LocalThemeChoice=staticCompositionLocalOf {"System"}

internal val LocalSetTheme=staticCompositionLocalOf<(String)->Unit> {{}}

internal val LocalBrand=staticCompositionLocalOf {pk.raheeloptics.orders.branding.BrandAppearance(pk.raheeloptics.orders.branding.BrandConfig("Raheel Optics","Clear vision. Personal care."))}

@Composable
fun AppTheme(content:@Composable ()->Unit) {
    val context=LocalContext.current
    val themePrefs=remember {context.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)}
    var themeChoice by remember {mutableStateOf(themePrefs.getString("theme","System") ?: "System")}
    val dark=when(themeChoice) {"Dark"->true;"Light"->false;else->androidx.compose.foundation.isSystemInDarkTheme()}
    SideEffect {
        (context as? android.app.Activity)?.let {activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    CompositionLocalProvider(LocalThemeChoice provides themeChoice,LocalSetTheme provides {choice ->
        themePrefs.edit {putString("theme",choice)};themeChoice=choice
        syncLaunchTheme(context)
        if(Build.VERSION.SDK_INT<31) (context as? android.app.Activity)?.recreate()
    }) {MaterialTheme(colorScheme=if(dark) darkScheme else lightScheme,content=content)}
}
