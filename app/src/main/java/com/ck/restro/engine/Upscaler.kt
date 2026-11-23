package com.ck.restro.engine

import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs a 4× super-resolution network over a photo in overlapping tiles (so memory stays small
 * on any phone) and draws each finished tile straight into an output of any size.
 */
object Upscaler {
    const val SCALE = 4

    fun interface Progress { fun onTile(done: Int, total: Int) }

    /**
     * @param tile tile edge in input pixels (smaller for the heavy Ultra network)
     * @param cancelled polled between tiles
     */
    fun run(
        session: OrtSession, src: Bitmap, outW: Int, outH: Int, tile: Int,
        progress: Progress? = null, cancelled: () -> Boolean = { false },
    ): Bitmap {
        val w = src.width; val h = src.height
        val pad = 10
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val fx = outW.toFloat() / w
        val fy = outH.toFloat() / h
        val cols = (w + tile - 1) / tile
        val rows = (h + tile - 1) / tile
        val total = cols * rows
        var done = 0

        for (ty in 0 until rows) for (tx in 0 until cols) {
            if (cancelled()) throw CancelledException()
            val x0 = tx * tile; val y0 = ty * tile
            val x1 = min(x0 + tile, w); val y1 = min(y0 + tile, h)
            val px0 = max(x0 - pad, 0); val py0 = max(y0 - pad, 0)
            val px1 = min(x1 + pad, w); val py1 = min(y1 + pad, h)
            val tw = px1 - px0; val th = py1 - py0

            val pixels = IntArray(tw * th)
            src.getPixels(pixels, 0, tw, px0, py0, tw, th)
            val input = toPlanar(pixels, tw, th)
            val (o, _) = Ort.run(session, input, th, tw)
            val ow = tw * SCALE; val oh = th * SCALE

            // keep only the tile's own area (drop the overlap)
            val cx = (x0 - px0) * SCALE; val cy = (y0 - py0) * SCALE
            val cw = (x1 - x0) * SCALE; val ch = (y1 - y0) * SCALE
            val tileBmp = fromPlanar(o, ow, oh, cx, cy, cw, ch)

            val dst = Rect((x0 * fx).roundToInt(), (y0 * fy).roundToInt(), (x1 * fx).roundToInt(), (y1 * fy).roundToInt())
            val shrunk = shrinkForDraw(tileBmp, dst.width(), dst.height())
            canvas.drawBitmap(shrunk, null, dst, paint)
            if (shrunk !== tileBmp) shrunk.recycle()
            tileBmp.recycle()
            done++
            progress?.onTile(done, total)
        }
        return out
    }

    /** Whole small image in one go (used for a 512 px face). */
    fun runWhole(session: OrtSession, src: Bitmap, tile: Int = 256): Bitmap =
        run(session, src, src.width * SCALE, src.height * SCALE, tile)

    /** ARGB ints -> 1×3×H×W floats in 0..1 (RGB). */
    fun toPlanar(px: IntArray, w: Int, h: Int, lo: Float = 0f, hi: Float = 1f): FloatArray {
        val n = w * h
        val f = FloatArray(3 * n)
        val k = (hi - lo) / 255f
        for (i in 0 until n) {
            val c = px[i]
            f[i] = ((c shr 16) and 0xFF) * k + lo
            f[n + i] = ((c shr 8) and 0xFF) * k + lo
            f[2 * n + i] = (c and 0xFF) * k + lo
        }
        return f
    }

    /** 1×3×H×W floats (range lo..hi) -> a bitmap of the crop (cx, cy, cw, ch). */
    fun fromPlanar(f: FloatArray, w: Int, h: Int, cx: Int, cy: Int, cw: Int, ch: Int, lo: Float = 0f, hi: Float = 1f): Bitmap {
        val n = w * h
        val k = 255f / (hi - lo)
        val px = IntArray(cw * ch)
        for (y in 0 until ch) {
            val row = (cy + y) * w + cx
            val o = y * cw
            for (x in 0 until cw) {
                val i = row + x
                val r = ((f[i] - lo) * k + 0.5f).toInt().coerceIn(0, 255)
                val g = ((f[n + i] - lo) * k + 0.5f).toInt().coerceIn(0, 255)
                val b = ((f[2 * n + i] - lo) * k + 0.5f).toInt().coerceIn(0, 255)
                px[o + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /** Bilinear drawing aliases when shrinking more than 2×: halve first so detail stays smooth. */
    fun shrinkForDraw(b: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = b
        while (cur.width / 2 >= tw * 1.5f && cur.height / 2 >= 1 && cur.width / 2 >= 1) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, max(1, cur.height / 2), true)
            if (cur !== b) cur.recycle()
            cur = next
        }
        return cur
    }
}

class CancelledException : RuntimeException("Cancelled")
