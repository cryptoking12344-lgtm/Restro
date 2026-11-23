package com.ck.restro.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object ImageIO {

    /** Original pixel size without decoding the photo. */
    fun size(ctx: Context, uri: Uri): Pair<Int, Int> {
        var wh = 0 to 0
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { d, info, _ ->
            wh = info.size.width to info.size.height
            d.setTargetSize(1, 1)
        }.recycle()
        return wh
    }

    /** Decode (EXIF rotation applied), shrunk so its long side is at most [maxSide]. */
    fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val b = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = false
            val w = info.size.width; val h = info.size.height
            val long = max(w, h)
            if (long > maxSide) {
                val k = maxSide.toFloat() / long
                d.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
            }
        }
        if (b.config == Bitmap.Config.ARGB_8888 && !b.hasAlpha()) return b
        // transparent PNGs go on white; other formats become plain ARGB_8888
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(b, 0f, 0f, null)
        b.recycle()
        return out
    }

    /** Save into Pictures/Restro and return its gallery uri. */
    fun save(ctx: Context, b: Bitmap, png: Boolean): Uri {
        val name = "Restro_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + if (png) ".png" else ".jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        val fmt = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw RuntimeException("Couldn't create the file")
            cr.openOutputStream(uri)!!.use { b.compress(fmt, 95, it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Restro").apply { mkdirs() }
        val f = File(dir, name)
        f.outputStream().use { b.compress(fmt, 95, it) }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null)
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            @Suppress("DEPRECATION") put(MediaStore.Images.Media.DATA, f.path)
        }
        return cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: Uri.fromFile(f)
    }
}
