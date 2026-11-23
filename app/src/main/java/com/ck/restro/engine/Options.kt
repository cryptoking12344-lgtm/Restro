package com.ck.restro.engine

import android.content.Context

/** Which upscaling network runs the photo. */
enum class Engine { FAST, ULTRA, ANIME }

/** How big the restored photo comes out. */
enum class OutSize { ORIGINAL, X2, X4, K4 }

/** Every choice the Studio page offers. Kept per device between launches. */
data class RestoreOptions(
    var mode: Int = MODE_PORTRAIT,
    var engine: Engine = Engine.ULTRA,
    var size: OutSize = OutSize.K4,
    /** 0 keep grain .. 2 clean (Fast engine only). */
    var denoise: Int = 1,
    var faces: Boolean = true,
    /** 0..100 % of the restored face blended over the upscaled one. */
    var faceStrength: Int = 90,
    /** Run the restored face through the upscaler again so it holds up at 4K. */
    var faceBoost: Boolean = true,
    /** 0..100 % unsharp mask on the finished photo. */
    var sharpen: Int = 0,
    var autoColor: Boolean = false,
    var png: Boolean = false,
) {
    fun applyMode(m: Int) {
        mode = m
        when (m) {
            MODE_QUICK -> set(Engine.FAST, OutSize.X2, 1, faces = false, strength = 80, boost = false, sharpen = 0, color = false)
            MODE_PHOTO -> set(Engine.ULTRA, OutSize.K4, 1, faces = false, strength = 80, boost = false, sharpen = 10, color = false)
            MODE_PORTRAIT -> set(Engine.ULTRA, OutSize.K4, 1, faces = true, strength = 90, boost = true, sharpen = 0, color = false)
            MODE_OLD -> set(Engine.FAST, OutSize.K4, 2, faces = true, strength = 80, boost = true, sharpen = 20, color = true)
            MODE_ANIME -> set(Engine.ANIME, OutSize.X4, 1, faces = false, strength = 80, boost = false, sharpen = 0, color = false)
        }
    }

    private fun set(e: Engine, s: OutSize, d: Int, faces: Boolean, strength: Int, boost: Boolean, sharpen: Int, color: Boolean) {
        engine = e; size = s; denoise = d; this.faces = faces; faceStrength = strength; faceBoost = boost
        this.sharpen = sharpen; autoColor = color
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putInt("mode", mode).putInt("engine", engine.ordinal).putInt("size", size.ordinal)
            .putInt("denoise", denoise).putBoolean("faces", faces).putInt("faceStrength", faceStrength)
            .putBoolean("faceBoost", faceBoost).putInt("sharpen", sharpen).putBoolean("autoColor", autoColor)
            .putBoolean("png", png).apply()
    }

    companion object {
        const val MODE_QUICK = 0
        const val MODE_PHOTO = 1
        const val MODE_PORTRAIT = 2
        const val MODE_OLD = 3
        const val MODE_ANIME = 4
        const val MODE_CUSTOM = 5

        val MODE_NAMES = arrayOf("Quick", "Photo", "Portrait", "Old photo", "Anime")
        val MODE_DESCS = arrayOf(
            "Fast clean-up and 2× size. Good for screenshots and quick shares.",
            "Ultra detail upscale to 4K. Best for landscapes, food and products.",
            "Ultra detail 4K plus AI face restoration. Best for selfies and people.",
            "Strong noise and blur cleanup, faces restored, colour revived. For scans and old phone photos.",
            "Line-art friendly upscale for anime, cartoons and illustrations.",
            "Your own mix of settings.",
        )

        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("restro_opts", Context.MODE_PRIVATE)

        fun load(ctx: Context): RestoreOptions {
            val p = prefs(ctx)
            if (!p.contains("mode")) return RestoreOptions().also { it.applyMode(MODE_PORTRAIT) }
            return RestoreOptions(
                mode = p.getInt("mode", MODE_PORTRAIT),
                engine = Engine.entries.getOrElse(p.getInt("engine", 1)) { Engine.ULTRA },
                size = OutSize.entries.getOrElse(p.getInt("size", 3)) { OutSize.K4 },
                denoise = p.getInt("denoise", 1).coerceIn(0, 2),
                faces = p.getBoolean("faces", true),
                faceStrength = p.getInt("faceStrength", 90).coerceIn(0, 100),
                faceBoost = p.getBoolean("faceBoost", true),
                sharpen = p.getInt("sharpen", 0).coerceIn(0, 100),
                autoColor = p.getBoolean("autoColor", false),
                png = p.getBoolean("png", false),
            )
        }
    }
}
