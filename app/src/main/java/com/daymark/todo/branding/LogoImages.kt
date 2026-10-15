package com.daymark.todo.branding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

internal object LogoImages {
    const val MaxBytes=262144
    const val MaxEdge=384
    fun decode(bytes:ByteArray?):Bitmap? {
        if(bytes==null || bytes.size>MaxBytes) return null
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
        // Bounds are already limited to 512px. Set the platform sample explicitly;
        // Options.inSampleSize can be zero before decoding and must not be a divisor.
        val options=BitmapFactory.Options().apply {inSampleSize=1}
        val original=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: return null
        val edge=maxOf(original.width,original.height)
        if(edge<=MaxEdge) return original
        val scale=MaxEdge.toFloat()/edge
        return original.scale((original.width*scale).toInt().coerceAtLeast(1),(original.height*scale).toInt().coerceAtLeast(1)).also {
            if(it!==original) original.recycle()
        }
    }
    fun encode(bitmap:Bitmap):ByteArray {
        val output=ByteArrayOutputStream()
        val format=if(Build.VERSION.SDK_INT>=30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
        check(bitmap.compress(format,100,output)) {"Couldn't cache the logo."}
        return output.toByteArray().also {check(it.size<=MaxBytes)}
    }
}
