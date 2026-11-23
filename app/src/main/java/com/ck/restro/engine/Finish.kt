package com.ck.restro.engine

import android.graphics.Bitmap

/** Last touches on the restored photo: gentle auto colour and an unsharp-mask sharpen. */
object Finish {

    /** Per-channel levels from a small copy: stretch so the darkest/brightest 0.4 % clip. */
    fun colorLut(b: Bitmap): Array<IntArray> {
        val s = Bitmap.createScaledBitmap(b, 256, (256f * b.height / b.width).toInt().coerceAtLeast(1), true)
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        if (s !== b) s.recycle()
        val hist = Array(3) { IntArray(256) }
        for (c in px) {
            hist[0][(c shr 16) and 0xFF]++; hist[1][(c shr 8) and 0xFF]++; hist[2][c and 0xFF]++
        }
        val clip = (px.size * 0.004f).toInt()
        return Array(3) { ch ->
            val h = hist[ch]
            var lo = 0; var acc = 0
            while (lo < 255 && acc + h[lo] <= clip) { acc += h[lo]; lo++ }
            var hi = 255; acc = 0
            while (hi > 0 && acc + h[hi] <= clip) { acc += h[hi]; hi-- }
            lo = lo.coerceAtMost(48); hi = hi.coerceAtLeast(200)
            IntArray(256) { v ->
                val stretched = ((v - lo) * 255f / (hi - lo).coerceAtLeast(1)).coerceIn(0f, 255f)
                (v + (stretched - v) * 0.65f + 0.5f).toInt().coerceIn(0, 255)   // 65 % of the way: natural
            }
        }
    }

    /**
     * One pass over [b] in place, three rows at a time (memory stays tiny even at 8K).
     * [amount] 0..1 sharpen, [lut] optional colour curves.
     */
    fun apply(b: Bitmap, amount: Float, lut: Array<IntArray>?, cancelled: () -> Boolean) {
        val w = b.width; val h = b.height
        if (amount <= 0f && lut == null) return
        val a = (amount * 1.6f * 256).toInt()   // fixed-point strength
        var prev = IntArray(w); var cur = IntArray(w); var next = IntArray(w)
        val outRow = IntArray(w)
        b.getPixels(cur, 0, w, 0, 0, w, 1)
        prev = cur.copyOf()
        for (y in 0 until h) {
            if (y % 256 == 0 && cancelled()) throw CancelledException()
            if (y + 1 < h) b.getPixels(next, 0, w, 0, y + 1, w, 1) else System.arraycopy(cur, 0, next, 0, w)
            for (x in 0 until w) {
                val c = cur[x]
                var r = (c shr 16) and 0xFF; var g = (c shr 8) and 0xFF; var bl = c and 0xFF
                if (a > 0) {
                    val xl = if (x > 0) x - 1 else x; val xr = if (x < w - 1) x + 1 else x
                    // 3×3 blur (centre-weighted) for the unsharp mask
                    val br = blur(prev, cur, next, xl, x, xr, 16)
                    val bg = blur(prev, cur, next, xl, x, xr, 8)
                    val bb = blur(prev, cur, next, xl, x, xr, 0)
                    r = (r + (((r - br) * a) shr 8)).coerceIn(0, 255)
                    g = (g + (((g - bg) * a) shr 8)).coerceIn(0, 255)
                    bl = (bl + (((bl - bb) * a) shr 8)).coerceIn(0, 255)
                }
                if (lut != null) { r = lut[0][r]; g = lut[1][g]; bl = lut[2][bl] }
                outRow[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
            b.setPixels(outRow, 0, w, 0, y, w, 1)
            val t = prev; prev = cur; cur = next; next = t
        }
    }

    private fun blur(p: IntArray, c: IntArray, n: IntArray, l: Int, m: Int, r: Int, sh: Int): Int {
        fun v(a: IntArray, i: Int) = (a[i] shr sh) and 0xFF
        val sum = v(p, l) + 2 * v(p, m) + v(p, r) +
            2 * v(c, l) + 4 * v(c, m) + 2 * v(c, r) +
            v(n, l) + 2 * v(n, m) + v(n, r)
        return sum shr 4
    }
}
