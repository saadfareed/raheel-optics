package com.daymark.todo

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.ui.SplashScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SplashThemeTest {
    @get:Rule val compose=createComposeRule()

    @Test fun taglineScreenUsesTheActiveLightAndDarkBackground() {
        val dark=mutableStateOf(false)
        val lightBackground=Color(0xFFF5F9FC)
        val darkBackground=Color(0xFF14131C)
        compose.setContent {
            MaterialTheme(colorScheme=if(dark.value) darkColorScheme(background=darkBackground)
                else lightColorScheme(background=lightBackground)) {SplashScreen()}
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val brand=(context.applicationContext as DaymarkApp).branding.state.value.config
        fun verify(name:String,expected:Color) {
            compose.onNodeWithText(brand.name).assertIsDisplayed()
            compose.onNodeWithText(brand.tagline).assertIsDisplayed()
            val image=compose.onRoot().captureToImage()
            assertEquals(expected.toArgb(),image.toPixelMap()[4,4].toArgb())
            java.io.File(context.cacheDir,"splash-$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
        verify("light",lightBackground)
        compose.runOnIdle {dark.value=true}
        verify("dark",darkBackground)
    }

    @Test fun nativeLaunchBackgroundHasMatchingDayAndNightResources() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for((mode,expected) in listOf(Configuration.UI_MODE_NIGHT_NO to Color(0xFFF5F9FC),
            Configuration.UI_MODE_NIGHT_YES to Color(0xFF14131C))) {
            val config=Configuration(context.resources.configuration)
            config.uiMode=(config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            val themed=context.createConfigurationContext(config)
            assertEquals(expected.toArgb(),themed.getColor(R.color.splash_background_color))
            assertEquals(mode==Configuration.UI_MODE_NIGHT_NO,themed.resources.getBoolean(R.bool.light_system_bars))
        }
    }
}
