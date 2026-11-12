package pk.raheeloptics.orders.ui

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File

object FrameImages {
    /** A stored frame photo never exceeds this size. */
    const val MAX_BYTES=1_000_000

    fun import(context:Context,uri:Uri):String {
        val resolver=context.contentResolver
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        resolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)} ?: error("Cannot open this photo.")
        require(bounds.outWidth>0 && bounds.outHeight>0) {"Choose a supported photo."}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2560) sample*=2
        val bitmap=resolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=sample})} ?: error("Cannot decode this photo.")
        val orientation=runCatching {resolver.openInputStream(uri)?.use {android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}}.getOrNull()
        val matrix=Matrix().apply {when(orientation) {
            2 ->postScale(-1f,1f);3 ->postRotate(180f);4 ->postScale(1f,-1f)
            5 ->{postRotate(90f);postScale(-1f,1f)};6 ->postRotate(90f);7 ->{postRotate(270f);postScale(-1f,1f)};8 ->postRotate(270f)
        }}
        val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
        // Saved photos are capped at 1 MB: shrink quality, then size, until the JPEG fits.
        var edge=minOf(1280f,maxOf(rotated.width,rotated.height).toFloat())
        var bytes:ByteArray?=null
        val made=mutableListOf<Bitmap>()
        try {
            while(bytes==null && edge>=320f) {
                val scale=minOf(1f,edge/maxOf(rotated.width,rotated.height))
                val resized=Bitmap.createScaledBitmap(rotated,(rotated.width*scale).toInt().coerceAtLeast(1),(rotated.height*scale).toInt().coerceAtLeast(1),true).also {made+=it}
                for(quality in intArrayOf(85,70,55,40)) {
                    val out=java.io.ByteArrayOutputStream()
                    check(resized.compress(Bitmap.CompressFormat.JPEG,quality,out)) {"Cannot save the frame photo."}
                    if(out.size()<=MAX_BYTES) {bytes=out.toByteArray();break}
                }
                edge*=0.75f
            }
            val data=bytes ?: error("This photo can't be reduced below 1 MB. Choose a smaller photo.")
            val folder=File(context.filesDir,"frames").apply {check(isDirectory || mkdirs()) {"Cannot save the frame photo."}}
            val file=File(folder,"${java.util.UUID.randomUUID()}.jpg")
            try {file.writeBytes(data);return Uri.fromFile(file).toString()}
            catch(e:Exception) {file.delete();throw e}
        }
        finally {(made+setOf(bitmap,rotated)).forEach {if(!it.isRecycled) it.recycle()}}
    }
}
