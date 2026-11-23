package com.ck.restro.engine

import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GFPGAN face restoration: find faces (ML Kit), line each one up with the FFHQ 512 px face
 * template, restore it, then blend it back into the upscaled photo through a soft oval mask.
 */
object FaceRestorer {
    private const val SIZE = 512

    /** FFHQ 512 template: image-left eye, image-right eye, nose, mouth left, mouth right. */
    private val TEMPLATE = arrayOf(
        PointF(192.98138f, 239.94708f), PointF(318.90277f, 240.1936f), PointF(256.63416f, 314.01935f),
        PointF(201.26117f, 371.41043f), PointF(313.08905f, 371.15118f),
    )

    class Face(val toTemplate: Matrix, val sizeInSrc: Float)

    /** Faces with their src -> 512 template transform, largest first. */
    fun detect(src: Bitmap): List<Face> {
        // detection is faster and just as good on a smaller copy
        val long = max(src.width, src.height)
        val k = if (long > 1600) 1600f / long else 1f
        val img = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build()
        )
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(img, 0)))
        } finally {
            detector.close()
            if (img !== src) img.recycle()
        }
        val out = ArrayList<Face>()
        for (f in faces) {
            fun lm(t: Int) = f.getLandmark(t)?.position?.let { PointF(it.x / k, it.y / k) }
            val e1 = lm(FaceLandmark.LEFT_EYE) ?: continue
            val e2 = lm(FaceLandmark.RIGHT_EYE) ?: continue
            val nose = lm(FaceLandmark.NOSE_BASE)
            val m1 = lm(FaceLandmark.MOUTH_LEFT)
            val m2 = lm(FaceLandmark.MOUTH_RIGHT)
            val eyes = listOf(e1, e2).sortedBy { it.x }
            val srcPts = ArrayList<PointF>(); val dstPts = ArrayList<PointF>()
            srcPts += eyes[0]; dstPts += TEMPLATE[0]
            srcPts += eyes[1]; dstPts += TEMPLATE[1]
            if (nose != null) { srcPts += nose; dstPts += TEMPLATE[2] }
            if (m1 != null && m2 != null) {
                val mouth = listOf(m1, m2).sortedBy { it.x }
                srcPts += mouth[0]; dstPts += TEMPLATE[3]
                srcPts += mouth[1]; dstPts += TEMPLATE[4]
            }
            val (m, scale) = similarity(srcPts, dstPts)
            out += Face(m, SIZE / scale)
        }
        return out.sortedByDescending { it.sizeInSrc }.take(8)
    }

    /** Least-squares rotation + uniform scale + shift taking [a] onto [b]. */
    private fun similarity(a: List<PointF>, b: List<PointF>): Pair<Matrix, Float> {
        val n = a.size
        var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
        for (i in 0 until n) { ax += a[i].x; ay += a[i].y; bx += b[i].x; by += b[i].y }
        ax /= n; ay /= n; bx /= n; by /= n
        var num1 = 0f; var num2 = 0f; var den = 0f
        for (i in 0 until n) {
            val x = a[i].x - ax; val y = a[i].y - ay
            val u = b[i].x - bx; val v = b[i].y - by
            num1 += x * u + y * v
            num2 += x * v - y * u
            den += x * x + y * y
        }
        val p = num1 / den   // s·cos
        val q = num2 / den   // s·sin
        val tx = bx - (p * ax - q * ay)
        val ty = by - (q * ax + p * ay)
        val m = Matrix()
        m.setValues(floatArrayOf(p, -q, tx, q, p, ty, 0f, 0f, 1f))
        return m to sqrt(p * p + q * q)
    }

    /** Cut the face out of [src] onto the 512 template. */
    fun crop(src: Bitmap, face: Face): Bitmap {
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(132, 133, 135))
        c.drawBitmap(src, face.toTemplate, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return b
    }

    /** GFPGAN: 512 face in, restored 512 face out (the network works in -1..1). */
    fun restore(session: OrtSession, crop: Bitmap): Bitmap {
        val px = IntArray(SIZE * SIZE)
        crop.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val (out, _) = Ort.run(session, Upscaler.toPlanar(px, SIZE, SIZE, -1f, 1f), SIZE, SIZE)
        return Upscaler.fromPlanar(out, SIZE, SIZE, 0, 0, SIZE, SIZE, -1f, 1f)
    }

    private var maskCache: Bitmap? = null

    /** Soft oval over the face (template space): solid in the middle, fading out by the edges. */
    private fun mask(): Bitmap {
        maskCache?.let { return it }
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        val lm = Matrix(); lm.setScale(225f, 290f); lm.postTranslate(256f, 268f)
        g.setLocalMatrix(lm)
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = g })
        maskCache = b
        return b
    }

    /**
     * Paste [restored] (any multiple of 512 px) back into [out], which is [src] scaled by
     * (sx, sy). [strength] 0..1.
     */
    fun paste(out: Bitmap, face: Face, restored: Bitmap, sx: Float, sy: Float, strength: Float) {
        val inv = Matrix()
        if (!face.toTemplate.invert(inv)) return
        val toOut = Matrix(inv).apply { postScale(sx, sy) }           // template -> output
        val faceM = Matrix().apply {
            val k = SIZE.toFloat() / restored.width
            setScale(k, k); postConcat(toOut)
        }
        val bounds = RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())
        toOut.mapRect(bounds)
        bounds.intersect(0f, 0f, out.width.toFloat(), out.height.toFloat())
        if (bounds.isEmpty) return

        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val save = c.saveLayerAlpha(bounds, (strength.coerceIn(0f, 1f) * 255).toInt())
        c.drawBitmap(restored, faceM, p)
        val mp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawBitmap(mask(), toOut, mp)
        c.restoreToCount(save)
    }

    /** How many output pixels the 512 face spans once pasted. */
    fun outputSize(face: Face, sx: Float) = min(face.sizeInSrc * sx, 8192f)
}
