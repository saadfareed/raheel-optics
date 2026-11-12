package pk.raheeloptics.orders

import android.graphics.Bitmap
import pk.raheeloptics.orders.branding.LogoImages
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class LogoDecodeTest {
    private fun png(width:Int,height:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            return ByteArrayOutputStream().also {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()
        } finally {bitmap.recycle()}
    }
    @Test fun validSmallLogoDoesNotDivideByDefaultSampleSize() {
        val decoded=LogoImages.decode(png(64,48))!!
        try {assertEquals(64,decoded.width);assertEquals(48,decoded.height);assertEquals(0,decoded.getPixel(0,0))}
        finally {decoded.recycle()}
    }
    @Test fun largeAllowedLogoKeepsAspectRatioAndOversizeIsRejected() {
        val decoded=LogoImages.decode(png(512,256))!!
        try {assertEquals(384,decoded.width);assertEquals(192,decoded.height)} finally {decoded.recycle()}
        assertNull(LogoImages.decode(png(513,1)))
    }
}
