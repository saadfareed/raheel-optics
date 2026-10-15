package com.daymark.todo

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.branding.LogoImages
import com.daymark.todo.onboarding.OnboardingStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class VisualAssetsTest {
    @Test fun logoCacheIsBoundedAndKeepsTransparency() {
        val bitmap=Bitmap.createBitmap(512,256,Bitmap.Config.ARGB_8888)
        bitmap.setPixel(100,100,android.graphics.Color.RED)
        val output=ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG,100,output)
        val optimized=LogoImages.decode(output.toByteArray())!!
        assertEquals(384,optimized.width);assertEquals(192,optimized.height)
        assertTrue(optimized.hasAlpha())
        assertEquals(0,android.graphics.Color.alpha(optimized.getPixel(0,0)))
        val bytes=LogoImages.encode(optimized)
        assertTrue(bytes.size<=LogoImages.MaxBytes)
        val roundTrip=LogoImages.decode(bytes)!!
        assertEquals(optimized.width,roundTrip.width)
        assertEquals(0,android.graphics.Color.alpha(roundTrip.getPixel(0,0)))
        assertNull(LogoImages.decode(ByteArray(LogoImages.MaxBytes+1)))
        assertNull(LogoImages.decode(byteArrayOf(0,1,2)))
        bitmap.recycle();optimized.recycle();roundTrip.recycle()
    }
    @Test fun completedIntroductionDoesNotReturnAfterRestartOrSignOut()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName="onboarding-test-${UUID.randomUUID()}"
        val context=object:ContextWrapper(base) {
            override fun databaseList()=emptyArray<String>()
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences(preferenceName,mode)
        }
        try {
            val first=OnboardingStore(context,false)
            assertTrue(first.required.value)
            first.complete()
            assertFalse(first.required.value)
            assertFalse(OnboardingStore(context,false).required.value)
            base.getSharedPreferences(preferenceName,Context.MODE_PRIVATE).edit().clear().commit()
            assertFalse(OnboardingStore(context,true).required.value)
        } finally {base.deleteSharedPreferences(preferenceName)}
    }
}
